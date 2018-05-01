package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

// See the original [RendezvousChannel] class for the contract details.
// Only implementation details are documented here.
class RendezvousChannelKoval<E>(
        private val segmentSize: Int = 64,
        private val spinThreshold: Int = 300,
        private val elemSpinThreshold: Int = 20
): ChannelKoval<E> {
    // Waiting queue node
    private class Node(segmentSize: Int, @JvmField val id: Int) {
        // Indexes for deque ([_deqIdx]) and enqueue ([_enqIdx]) operations
        // on the waiting queue. On each operation the required one should be
        // increment in order to perform enqueue or deque.
        //
        // Node is empty if these indexes are equals
        // and full if deque index is equals to the [segmentSize].
        @JvmField @Volatile var _deqIdx = 0
        @JvmField @Volatile var _enqIdx = 0

        // Pointer to the next node in the waiting queue,
        // maintained similar to the MS queue algorithm.
        @JvmField @Volatile var _next: Node? = null

        // This array contains the data of this segment. In order not to have
        // redundant cache misses, both values to be sent and continuations
        // are stored in the same array at indexes `2i` and `2i+1` respectively.
//        @JvmField val _data = AtomicReferenceArray<Any?>(segmentSize * 2)
        private val _data = arrayOfNulls<Any?>(segmentSize * 2)


        constructor(segmentSize: Int, id: Int, cont: CancellableContinuation<*>, element: Any)
                : this(segmentSize = segmentSize, id = id)
        {
            _enqIdx = 1
            _data[1] = cont
            _data[0] = element
        }

        inline fun putElementVolatile(index: Int, element: Any) {
            UNSAFE.putObjectVolatile(_data, byteOffset(index * 2), element)
        }

        inline fun putElementLazy(index: Int, element: Any) {
            UNSAFE.putOrderedObject(_data, byteOffset(index * 2), element)
        }

        inline fun getElementVolatile(index: Int): Any? {
            return UNSAFE.getObjectVolatile(_data, byteOffset(index * 2))
        }

        inline fun casElement(index: Int, expect: Any?, update: Any): Boolean {
            return UNSAFE.compareAndSwapObject(_data, byteOffset(index * 2), expect, update)
        }

        inline fun putContinuationLazy(index: Int, cont: Any) {
            UNSAFE.putOrderedObject(_data, byteOffset(index * 2 + 1), cont)
        }

        inline fun getContinuationWeak(index: Int): Any? {
            return UNSAFE.getObject(_data, byteOffset(index * 2 + 1))
        }
    }

    // These head and tail nodes are managed similar to MS queue.
    // For CAS operations [headUpdater] and [tailUpdater] are used.
    @Volatile private var _head: Node
    @Volatile private var _tail: Node

    init {
        // Initialize queue with empty node similar to MS queue
        // algorithm, but this node is just empty, not sentinel.
        val emptyNode = Node(segmentSize, 0)
        _head = emptyNode
        _tail = emptyNode
    }

    override suspend fun send(element: E) {
        // Try to send without suspending at first,
        // invoke suspend implementation if it is not succeed.
        if (offer(element)) return
        sendOrReceiveSuspend<Unit>(element!!)
    }

    override suspend fun receive(): E {
        // Try to send without suspending at first,
        // invoke suspend implementation if it is not succeed.
        return poll() ?: sendOrReceiveSuspend(RECEIVER_ELEMENT)
    }

    // Main function in this chanel, which implements both `#send` and `#receive` operations.
    // Note that `#offer` and `#poll` functions are just simplified versions of this one.
    private suspend fun <T> sendOrReceiveSuspend(element: Any) = suspendAtomicCancellableCoroutine<T>(holdCancellability = true) sc@ { curCont ->
        try_again@ while (true) { // CAS loop
            // Read the tail and its enqueue index at first, then the head and its indexes.
            // It is important to read tail and its index at first. If algorithm
            // realizes that the same continuations (senders or receivers) are stored
            // in the waiting queue, it can add the current continuation to the already
            // read tail index if it is not changed. In this case, it is guaranteed that
            // the waiting queue still has the same continuations or is empty.
            var tail = _tail
            var tailEnqIdx = tail._enqIdx
            var head = _head
            var headDeqIdx = head._deqIdx
            val headEnqIdx = head._enqIdx
            // If the waiting queue is empty, `headDeqIdx == headEnqIdx`.
            // This can also happen if the `head` node is full (`headDeqIdx == segmentSize`).
            if (headDeqIdx == headEnqIdx) {
                if (headDeqIdx == segmentSize) {
                    // The `head` node is full. Try to move `_head`
                    // pointer forward and start the operation again.
                    if (adjustHead(head)) continue@try_again
                    // Queue is empty, try to add a new node with the current continuation.
                    if (addNewNode(head, curCont, element)) return@sc
                } else {
                    // The `head` node is not full, therefore the waiting queue
                    // is empty. Try to add the current continuation to the queue.
                    if (storeContinuation(head, headEnqIdx, curCont, element))
                        return@sc
                }
            } else {
                // The waiting queue is not empty and it is guaranteed that `headDeqIdx < headEnqIdx`.
                // Try to remove the opposite continuation (a sender for a receiver or a receiver for a sender)
                // if waiting queue stores such in the `head` node at `headDeqIdx` index. In case the waiting
                // queue stores the same continuations, try to add the current continuation to it.
                //
                // In order to determine which continuations are stored, read the element from `head` node
                // at index `headDeqIdx`. When the algorithm add the continuation, it claims a slot at first,
                // stores the continuation and the element after that. This way, it is not guaranteed that
                // the first element is stored. The main idea is to spin on the value a bit and then change
                // element value from `null` to `TAKEN_ELEMENT` and increment the deque index if it is not appeared.
                // In this case the operation should start again. This simple  approach guarantees obstruction-freedom.
                // TODO make it lock-free using descriptors
                var firstElement = readElement(head, headDeqIdx)
                if (firstElement == TAKEN_ELEMENT) {
                    // Try to move the deque index in the `head` node
                    deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                    continue@try_again
                }
                // The `firstElement` is either sender or receiver. Check if a rendezvous is possible
                // and try to remove the first element in this case, try to add the current
                // continuation to the waiting queue otherwise.
                val makeRendezvous = if (element == RECEIVER_ELEMENT) firstElement != RECEIVER_ELEMENT else firstElement == RECEIVER_ELEMENT
                // If removing the already read continuation fails (due to a failed CAS on moving `_deqIdx` forward)
                // it is possible not to try do the whole operation again, but to re-read new `_head` and its `_deqIdx`
                // values and try to remove this continuation if it is located between the already read deque
                // and enqueue positions. In this case it is guaranteed that the queue contains the same
                // continuation types as on making the rendezvous decision. The same optimization is possible
                // for adding the current continuation to the waiting queue if it fails.
                val headIdLimit = tail.id
                val headDeqIdxLimit = tailEnqIdx
                if (makeRendezvous) {
                    while (true) {
                        if (tryResumeContinuation(head, headDeqIdx, element)) {
                            // The rendezvous is happened, congratulations!
                            // Resume the current continuation
                            val result = (if (element == RECEIVER_ELEMENT) firstElement else Unit) as T
                            curCont.resume(result)
                            return@sc
                        }
                        // Re-read the required pointers
                        read_state@ while (true) {
                            // Re-read head pointer and its deque index
                            head = _head
                            headDeqIdx = head._deqIdx
                            if (headDeqIdx == segmentSize) {
                                if (!adjustHead(head)) continue@try_again
                                continue@read_state
                            }
                            // Check that `(head.id, headDeqIdx) < (headIdLimit, headDeqIdxLimit)`
                            // and re-start the whole operation if needed
                            if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit)) continue@try_again
                            // Re-read the first element
                            firstElement = readElement(head, headDeqIdx)
                            if (firstElement == TAKEN_ELEMENT) {
                                deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                                continue@read_state
                            }
                            break@read_state
                        }
                    }
                } else {
                    while (true) {
                        // Try to add a new node with the current continuation and element
                        // if the tail is full, otherwise try to store it at the `tailEnqIdx` index.
                        if (tailEnqIdx == segmentSize) {
                            if (addNewNode(tail, curCont, element)) return@sc
                        } else {
                            if (storeContinuation(tail, tailEnqIdx, curCont, element)) return@sc
                        }
                        // Re-read the required pointers. Read tail and its indexes at first
                        // and only then head with its indexes.
                        tail = _tail
                        tailEnqIdx = tail._enqIdx
                        head = _head
                        headDeqIdx = head._deqIdx
                        if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit)) continue@try_again
                    }
                }
            }
        }
    }

    @Volatile private var _senderElimSize = ELIM_SIZE_MULTIPLIER + 1
    @Volatile private var _receiverElimSize = ELIM_SIZE_MULTIPLIER + 1

    private val _senderElements = AtomicReferenceArray<Any>(ELIM_MAX_ARR_SIZE)
    private val _receiverElements = AtomicReferenceArray<Any>(ELIM_MAX_ARR_SIZE)

    private class ElementBox(val value: Any)
    private class Done(val value: Any)

    private fun tryEliminateSender(element: Any): Boolean {
        var maxDequeIndex = _receiverElimSize
        if (maxDequeIndex < ELIM_MIN_SIZE) maxDequeIndex = ELIM_MIN_SIZE
        if (maxDequeIndex > ELIM_MAX_SIZE) maxDequeIndex = ELIM_MAX_SIZE
        deque@ while (true) {
            val dequeIndex = ThreadLocalRandom.current().nextInt(maxDequeIndex) / ELIM_SIZE_MULTIPLIER - 1
            if (dequeIndex != -1) {
                val x = _receiverElements[dequeIndex]
                when (x) {
                    null -> { _receiverElimSize--; break@deque }
                    ELIM_RECEIVER_ELEMENT -> {
                        if (_receiverElements.compareAndSet(dequeIndex, x, Done(element))) {
                            return true
                        } else { _receiverElimSize++; continue@deque }
                    }
                    else -> { _receiverElimSize++; continue@deque }
                }
            } else break@deque
        }

        var maxEnqueIndex = _senderElimSize
        if (maxEnqueIndex < ELIM_MIN_SIZE) maxEnqueIndex = ELIM_MIN_SIZE
        if (maxEnqueIndex > ELIM_MAX_SIZE) maxEnqueIndex = ELIM_MAX_SIZE
        enque@ while (true) {
            val enqueIndex = ThreadLocalRandom.current().nextInt(maxEnqueIndex) / ELIM_SIZE_MULTIPLIER - 1
            if (enqueIndex != -1) {
                val x = _senderElements[enqueIndex]
                if (x == null) {
                    val box = ElementBox(element)
                    if (_senderElements.compareAndSet(enqueIndex, null, box)) {
//                        _senderElimSize--
                        for (t in 1..elemSpinThreshold) {
                            val new = _senderElements.get(enqueIndex)
                            if (new == ELIM_SENDER_DONE) {
                                _senderElements[enqueIndex] = null
                                return true
                            }
                        }
                        if (!_senderElements.compareAndSet(enqueIndex, box, null)) {
                            _senderElements[enqueIndex] = null
                            return true
                        }
                        break@enque
                    } else { _senderElimSize++; continue@enque }
                } else { _senderElimSize++; break@enque }
            } else break@enque
        }

        return false
    }


    private fun tryEliminateReceiver(): Any? {
        var maxDequeIndex = _senderElimSize
        if (maxDequeIndex < ELIM_MIN_SIZE) maxDequeIndex = ELIM_MIN_SIZE
        if (maxDequeIndex > ELIM_MAX_SIZE) maxDequeIndex = ELIM_MAX_SIZE
        deque@ while (true) {
            val dequeIndex = ThreadLocalRandom.current().nextInt(maxDequeIndex) / ELIM_SIZE_MULTIPLIER - 1
            if (dequeIndex != -1) {
                val x = _senderElements[dequeIndex]
                when (x) {
                    null -> { _senderElimSize--; break@deque }
                    is ElementBox -> {
                        if (_senderElements.compareAndSet(dequeIndex, x, ELIM_SENDER_DONE)) {
                            return x.value
                        } else { _senderElimSize++; continue@deque }
                    }
                    else -> { _senderElimSize++; continue@deque }
                }
            } else break@deque
        }

        var maxEnqueIndex = _receiverElimSize
        if (maxEnqueIndex < ELIM_MIN_SIZE) maxEnqueIndex = ELIM_MIN_SIZE
        if (maxEnqueIndex > ELIM_MAX_SIZE) maxEnqueIndex = ELIM_MAX_SIZE
        enque@ while (true) {
            val enqueIndex = ThreadLocalRandom.current().nextInt(maxEnqueIndex) / ELIM_SIZE_MULTIPLIER - 1
            if (enqueIndex != -1) {
                val x = _receiverElements[enqueIndex]
                if (x == null) {
                    val box = ELIM_RECEIVER_ELEMENT
                    if (_receiverElements.compareAndSet(enqueIndex, null, box)) {
//                        _receiverElimSize--
                        for (t in 1..elemSpinThreshold) {
                            val new = _receiverElements.get(enqueIndex)
                            if (new is Done) {
                                _receiverElements[enqueIndex] = null
                                return new.value
                            }
                        }
                        if (!_receiverElements.compareAndSet(enqueIndex, box, null)) {
                            val res = (_receiverElements[enqueIndex] as Done).value
                            _receiverElements[enqueIndex] = null
                            return res
                        }
                        break@enque
                    } else { _receiverElimSize++; continue@enque }
                } else { _receiverElimSize++; break@enque }
            } else break@enque
        }

        return null
    }

    // This method is based on `#sendOrReceiveSuspend`. Returns `null` if fails.
    private fun <T> sendOrReceiveNonSuspend(element: Any): T? {
        if (element == RECEIVER_ELEMENT) {
            val res = tryEliminateReceiver()
            if (res != null) return res as T
        } else {
            if (tryEliminateSender(element)) return Unit as T
        }
        try_again@ while (true) { // CAS loop
            // Read the tail and its enqueue index at first, then the head and its indexes.
            val tail = _tail
            val tailEnqIdx = tail._enqIdx
            var head = _head
            var headDeqIdx = head._deqIdx
            val headEnqIdx = head._enqIdx
            // If the waiting queue is empty, `headDeqIdx == headEnqIdx`.
            // This can also happen if the `head` node is full (`headDeqIdx == segmentSize`).
            if (headDeqIdx == headEnqIdx) {
                if (headDeqIdx == segmentSize) {
                    // The `head` node is full. Try to move `_head`
                    // pointer forward and start the operation again.
                    if (adjustHead(head)) continue@try_again
                    // Queue is empty, return `null`
                    return null
                } else return null
            } else {
                // The waiting queue is not empty and it is guaranteed that `headDeqIdx < headEnqIdx`.
                // Try to remove the opposite continuation (a sender for a receiver or a receiver for a sender)
                // if waiting queue stores such in the `head` node at `headDeqIdx` index.
                var firstElement = readElement(head, headDeqIdx)
                if (firstElement == TAKEN_ELEMENT) {
                    // Try to move the deque index in the `head` node
                    deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                    continue@try_again
                }
                val makeRendezvous = if (element == RECEIVER_ELEMENT) firstElement != RECEIVER_ELEMENT else firstElement == RECEIVER_ELEMENT
                val headIdLimit = tail.id
                val headDeqIdxLimit = tailEnqIdx
                if (makeRendezvous) {
                    while (true) {
                        if (tryResumeContinuation(head, headDeqIdx, element)) {
                            // The rendezvous is happened, congratulations!
                            return (if (element == RECEIVER_ELEMENT) firstElement else Unit) as T
                        }
                        // Re-read the required pointers
                        read_state@ while (true) {
                            // Re-read head pointer and its deque index
                            head = _head
                            headDeqIdx = head._deqIdx
                            if (headDeqIdx == segmentSize) {
                                if (!adjustHead(head)) continue@try_again
                                continue@read_state
                            }
                            // Check that `(head.id, headDeqIdx) < (headIdLimit, headDeqIdxLimit)`
                            // and re-start the whole operation if needed
                            if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit)) continue@try_again
                            // Re-read the first element
                            firstElement = readElement(head, headDeqIdx)
                            if (firstElement == TAKEN_ELEMENT) {
                                deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                                continue@read_state
                            }
                            break@read_state
                        }
                    }
                } else return null
            }
        }
    }

    // Tries to move `_head` pointer forward if the current node is full.
    // Returns `false` if the waiting queue is empty, `true` on success.
    private fun adjustHead(head: Node): Boolean {
        // Read `_next` pointer and return `false` if the waiting queue is empty.
        val headNext = head._next ?: return false
        // Move `_head` forward. If the CAS fails, another thread moved it.
        headUpdater.compareAndSet(this, head, headNext)
        return true
    }

    // Adds a new node with the specified continuation and element to the tail. Work similar to MS queue.
    // If this method returns `true`, the add is successful and the operation invoked it is done.
    private fun addNewNode(tail: Node, cont: CancellableContinuation<*>, element: Any): Boolean {
        // If next node is not null, help to move the tail pointer
        val tailNext = tail._next
        if (tailNext != null) {
            // If this CAS fails, another thread moved the tail pointer
            tailUpdater.compareAndSet(this, tail, tailNext)
            return false
        }
        // Create a new node with this continuation and element and try to add it
        val node = Node(segmentSize, tail.id + 1, cont, element)
        if (nextUpdater.compareAndSet(tail, null, node)) {
            // New node added, try to move tail,
            // if the CAS fails, another thread moved it.
            tailUpdater.compareAndSet(this, tail, node)
            // Setup the continuation before suspend
            cont.initCancellability()
            cont.invokeOnCompletion { onCancel(tail, 0) }
            return true
        } else {
            // Next node is not null, help to move the tail pointer
            tailUpdater.compareAndSet(this, tail, tail._next)
            return false
        }
    }

    // Tries to read an element from the specified node
    // at the specified index. Returns the read element or
    // marks the slot as broken (sets `TAKEN_ELEMENT` to the slot)
    // and returns `TAKEN_ELEMENT` if the element is unavailable.
    private fun readElement(node: Node, index: Int): Any {
        // Element index in `Node#_data` array
//        val i = index * 2
        // Spin wait on the slot
//        var element = node._data[i]
        var element = node.getElementVolatile(index)
        var attempt = 0
        do {
            if (element != null) return element
//            element = node._data[i]
            element = node.getElementVolatile(index)
            attempt++
        } while (attempt < spinThreshold)
        // Cannot spin forever, mark the slot as broken if it is still unavailable
//        if (node._data.compareAndSet(i, null, TAKEN_ELEMENT)) {
        if (node.casElement(index, null, TAKEN_ELEMENT)) {
            return TAKEN_ELEMENT
        } else {
            // The element is set, read it and return
//            return node._data[i]!!
            return node.getElementVolatile(index)!!
        }
    }

    // Try to remove a continuation from the specified node at the
    // specified index and resume it. Returns `true` on success, `false` otherwise.
    private fun tryResumeContinuation(head: Node, dequeIndex: Int, element: Any): Boolean {
        // Try to move 'dequeIndex' forward, return `false` if fails
        if (deqIdxUpdater.compareAndSet(head, dequeIndex, dequeIndex + 1)) {
            // Get a continuation at the specified index
//            val cont = head._data[dequeIndex * 2 + 1] as CancellableContinuation<in Any>
            val cont = head.getContinuationWeak(dequeIndex) as CancellableContinuation<in Any>
            // Clear the slot to avoid memory leaks
//            head._data[dequeIndex * 2] = TAKEN_ELEMENT
//            head._data[dequeIndex * 2 + 1] = TAKEN_CONTINUATION
            head.putElementLazy(dequeIndex, TAKEN_ELEMENT)
            head.putContinuationLazy(dequeIndex, TAKEN_CONTINUATION)
            // Try to resume the continuation
            val value = if (element == RECEIVER_ELEMENT) Unit else element
            val token = cont.tryResume(value)
            if (token != null) {
                // The continuation is going to be resumed successfully
                cont.completeResume(token)
                // The continuation is resumed, return `true`
                return true
            } else {
                // The continuation has been cancelled, return `false`
                onCancel(head, dequeIndex)
                return false
            }
        } else return false
    }

    // Tries to store the current continuation and element (in this order!)
    // to the specified node at the specified index. Returns `true` on success,
    // `false` otherwise`.
    private fun storeContinuation(node: Node, index: Int, cont: CancellableContinuation<*>, element: Any): Boolean {
        // Try to move enqueue index forward, return `false` if fails
        if (!enqIdxUpdater.compareAndSet(node, index, index + 1)) return false
        // Slot `index` is claimed, try to store the continuation and the element (in this order!) to it.
        // Can fail if another thread marked this slot as broken, return `false` in this case.
//        node._data[index * 2 + 1] = cont
        node.putContinuationLazy(index, cont)
//        if (node._data.compareAndSet(index * 2, null, element)) {
        if (node.casElement(index, null, element)) {
            // Setup the continuation before suspend
            cont.initCancellability()
            cont.invokeOnCompletion { onCancel(node, index) }
            return true
        } else {
            // The slot is broken, clean it and return `false`
//            _data[index * 2 + 1] = TAKEN_CONTINUATION
            node.putContinuationLazy(index, TAKEN_CONTINUATION)
            return false
        }
    }

    // Maintains the number of cancelled continuations in the waiting queue
    private val _cancelledContinuations = AtomicInteger(0)

    // This method is invoked when a continuation stored in
    // the specified node by the specified index is cancelled.
    private fun onCancel(node: Node, index: Int) {
//        if (node._data.compareAndSet(index, cont, TAKEN_CONTINUATION)) {
            // The continuation was removed from the waiting queue by this thread.
            // Increment the number of cancelled continuations and
            // check if the waiting queue should be cleaned.
//            val cancelled = _cancelledContinuations.incrementAndGet()
//            val nodes = _tail.id - _head.id
//            if (cancelled >= nodes * segmentSize * CANCELLATION_THRESHOLD) {
//
//            }
//        }
        // TODO cancellation
    }

    override fun offer(element: E): Boolean {
        return sendOrReceiveNonSuspend<Unit>(element!!) != null
    }

    override fun poll(): E? {
       return sendOrReceiveNonSuspend(RECEIVER_ELEMENT)
    }

    override fun close(cause: Throwable?): Boolean {
        TODO("not implemented")
    }

    override val onSend: SelectClause2<E, ChannelKoval<E>>
        get() = TODO("not implemented")

    override val onReceive: SelectClause1<E>
        get() = TODO("not implemented")


    private companion object {
        const val ELIM_SIZE_MULTIPLIER = 1024
        @JvmField val ELIM_MAX_ARR_SIZE = Runtime.getRuntime().availableProcessors()
        @JvmField val ELIM_MIN_SIZE = ELIM_SIZE_MULTIPLIER + 1
        @JvmField val ELIM_MAX_SIZE = (ELIM_MAX_ARR_SIZE + 1) * ELIM_SIZE_MULTIPLIER
        @JvmField val ELIM_SENDER_DONE = Symbol("SENDER_DONE")
        @JvmField val ELIM_RECEIVER_ELEMENT = Symbol("RECEIVER_ELEMENT")

        @JvmField val UNSAFE = UtilUnsafe.unsafe
        @JvmField val base = UNSAFE.arrayBaseOffset(Array<Any>::class.java)
        @JvmField val shift = 31 - Integer.numberOfLeadingZeros(UNSAFE.arrayIndexScale(Array<Any>::class.java))

        @JvmStatic inline fun byteOffset(i: Int) = (i.toLong() shl shift) + base

        const val CANCELLATION_THRESHOLD = 0.75f

        @JvmField val TAKEN_ELEMENT = Symbol("TAKEN_ELEMENT")
        @JvmField val TAKEN_CONTINUATION = Symbol("TAKEN_CONTINUATION")
        @JvmField val RECEIVER_ELEMENT = Symbol("RECEIVER_ELEMENT")

        @JvmField val tailUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKoval::class.java, Node::class.java, "_tail")
        @JvmField val headUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKoval::class.java, Node::class.java, "_head")
        @JvmField val nextUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Node::class.java, "_next")

        @JvmField val deqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_deqIdx")
        @JvmField val enqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_enqIdx")
    }
}