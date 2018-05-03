package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import java.lang.Integer.max
import java.lang.Integer.min
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import java.util.concurrent.locks.LockSupport

// See the original [RendezvousChannel] class for the contract details.
// Only implementation details are documented here.
class RendezvousChannelKoval<E>(
        private val segmentSize: Int = 64,
        private val spinThreshold: Int = 300,
        private val elemSpinThreshold: Int = 15
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

        constructor(segmentSize: Int, id: Int, cont: Any, element: Any)
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

        inline fun putContinuationVolatile(index: Int, cont: Any) {
            UNSAFE.putObjectVolatile(_data, byteOffset(index * 2 + 1), cont)
        }

        inline fun getContinuationWeak(index: Int): Any? {
            return UNSAFE.getObject(_data, byteOffset(index * 2 + 1))
        }

        inline fun getContinuationVolatile(index: Int): Any? {
            return UNSAFE.getObjectVolatile(_data, byteOffset(index * 2 + 1))
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
        val res = poll()
        if (res != null) return res
        return sendOrReceiveSuspend(RECEIVER_ELEMENT)
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
                    if (adjustHead(head)) {
                        continue@try_again
                    }
                    // Queue is empty, try to add a new node with the current continuation.
                    if (addNewNode(head, curCont, element)) {
                        return@sc
                    } else { incElimReceiverArraySize(1); incElimSenderArraySize(1) }
                } else {
                    // The `head` node is not full, therefore the waiting queue
                    // is empty. Try to add the current continuation to the queue.
                    if (storeContinuation(head, headEnqIdx, curCont, element)) {
                        return@sc
                    } else { incElimReceiverArraySize(1); incElimSenderArraySize(1) }
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
                    incElimReceiverArraySize(1); incElimSenderArraySize(1)
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
                            if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit)) {
                                incElimReceiverArraySize(1); incElimSenderArraySize(1)
                                continue@try_again
                            }
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
                    read_state@ while (true) {
                        // Try to add a new node with the current continuation and element
                        // if the tail is full, otherwise try to store it at the `tailEnqIdx` index.
                        if (tailEnqIdx == segmentSize) {
                            if (addNewNode(tail, curCont, element)) {
                                decOppositeElimArraySize(element, 1)
                                return@sc
                            }
                        } else {
                            if (storeContinuation(tail, tailEnqIdx, curCont, element)) {
                                decOppositeElimArraySize(element, 1)
                                return@sc
                            }
                        }
                        // Re-read the required pointers. Read tail and its indexes at first
                        // and only then head with its indexes.
                        tail = _tail
                        tailEnqIdx = tail._enqIdx
                        head = _head
                        headDeqIdx = head._deqIdx
                        if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit)) {
                            incElimReceiverArraySize(1); incElimSenderArraySize(1)
                            continue@try_again
                        }
                    }
                }
            }
        }
    }

    @JvmField var _elimSenderArraySize = 0
    @JvmField var _elimReceiverArraySize = 0

    @JvmField var _elimsTotal = 0
    @JvmField var _elimsSucc = 0

    private val _elimSenderArray = AtomicReferenceArray<Any>(ELIM_MAX_ARR_SIZE)
    private val _elimReceiverArray = AtomicReferenceArray<Any>(ELIM_MAX_ARR_SIZE)

    private fun limitElimCounterValue(value: Int): Int {
        if (value < 0) return 0
        if (value > ELIM_MAX_ARR_SIZE) return ELIM_MAX_ARR_SIZE
        return value
    }

    private fun incElimSenderArraySize(value: Int) {
        val newVal = limitElimCounterValue(_elimSenderArraySize + value)
        _elimSenderArraySize = newVal
//        if (ThreadLocalRandom.current().nextInt(10000) == 0)
//            println("elimSenderArraySize=$newVal")
    }
    private fun decElimSenderArraySize(value: Int) = incElimSenderArraySize(-value)
    private fun incElimReceiverArraySize(value: Int) {
        val newVal = limitElimCounterValue(_elimReceiverArraySize + value)
        _elimReceiverArraySize = newVal
//        if (ThreadLocalRandom.current().nextInt(100000) == 0)
//            println("elimReceiverArraySize=$newVal")
    }
    private fun decElimReceiverArraySize(value: Int) = incElimReceiverArraySize(-value)
    private fun incOurElimArraySize(element: Any, value: Int) { if (element == RECEIVER_ELEMENT) incElimReceiverArraySize(value) else incElimSenderArraySize(value) }
    private fun decOurElimArraySize(element: Any, value: Int) = incOurElimArraySize(element, -value)
    private fun incOppositeElimArraySize(element: Any, value: Int) { if (element == RECEIVER_ELEMENT) incElimSenderArraySize(value) else incElimReceiverArraySize(value) }
    private fun decOppositeElimArraySize(element: Any, value: Int) = incOppositeElimArraySize(element, -value)

    private class ElementBox(val value: Any)
    private class Done(val value: Any)

    private fun tryEliminateSender(element: Any): Unit? {
        val elimReceiverArraySize = _elimReceiverArraySize
        if (elimReceiverArraySize > 0) {
            _elimsTotal++
            val position = ThreadLocalRandom.current().nextInt(elimReceiverArraySize)
            attempt@ for (i in max(0, position - 1) .. min(position + 1, elimReceiverArraySize - 1)) {
                val x = _elimReceiverArray[i]
                when (x) {
                    null -> { continue@attempt }
                    ELIM_RECEIVER_ELEMENT -> {
                        if (_elimReceiverArray.compareAndSet(i, x, Done(element))) {
                            _elimsSucc++
                            return Unit
                        } else incElimReceiverArraySize(1)
                    }
                }
            }
            // Elimination was unsuccessful :(
            decElimReceiverArraySize(1)
        }

        val elimSenderArraySize = _elimSenderArraySize
        if (elimSenderArraySize > 0) {
            _elimsTotal++
            val position = ThreadLocalRandom.current().nextInt(elimSenderArraySize)
            attempt@ for (i in max(0, position - 1) .. min(position + 1, elimSenderArraySize - 1)) {
                val x = _elimSenderArray[i]
                var box: ElementBox? = null
                when (x) {
                    null -> {
                        if (box == null) box = ElementBox(element)
                        if (_elimSenderArray.compareAndSet(i, null, box)) {
                            for (spinTime in 1 .. elemSpinThreshold) { // spin loop
                                val probablyDone = _elimSenderArray[i]
                                if (probablyDone == ELIM_SENDER_DONE) {
                                    _elimSenderArray[i] = null
                                    _elimsSucc++
                                    return Unit
                                }
                            }
                            if (!_elimSenderArray.compareAndSet(i, box, null)) {
                                // _elimSenderArray[i] == ELIM_SENDER_DONE
                                incElimSenderArraySize(1)
                                _elimSenderArray[i] = null
                                _elimsSucc++
                                return Unit
                            }
                        } else incElimSenderArraySize(1)
                    }
                }
            }
            decElimSenderArraySize(1)
        }

        return null
    }

    private fun tryEliminateReceiver(): Any? {
        val elimSenderArraySize = _elimSenderArraySize
        if (elimSenderArraySize > 0) {
            _elimsTotal++
            val position = ThreadLocalRandom.current().nextInt(elimSenderArraySize)
            attempt@ for (i in max(0, position - 1) .. min(position + 1, elimSenderArraySize - 1)) {
                val x = _elimSenderArray[i]
                when (x) {
                    null -> continue@attempt
                    is ElementBox -> {
                        if (_elimSenderArray.compareAndSet(i, x, ELIM_SENDER_DONE)) {
                            _elimsSucc++
                            return x.value
                        } else incElimSenderArraySize(1)
                    }
                }
            }
            // Elimination was unsuccessful :(
            decElimSenderArraySize(1)
        }

        val elimReceiverArraySize = _elimReceiverArraySize
        if (elimReceiverArraySize > 0) {
            _elimsTotal++
            val position = ThreadLocalRandom.current().nextInt(elimReceiverArraySize)
            attempt@ for (i in max(0, position - 1) .. min(position + 1, elimReceiverArraySize - 1)) {
                val x = _elimReceiverArray[i]
                when (x) {
                    null -> {
                        if (_elimReceiverArray.compareAndSet(i, null, ELIM_RECEIVER_ELEMENT)) {
                            for (spinTime in 1 .. elemSpinThreshold) {
                                val probablyDone = _elimReceiverArray[i]
                                if (probablyDone is Done) {
                                    val res = probablyDone.value
                                    _elimReceiverArray[i] = null
                                    _elimsSucc++
                                    return res
                                }
                            }
                            if (!_elimReceiverArray.compareAndSet(i, ELIM_RECEIVER_ELEMENT, null)) {
                                val done = _elimReceiverArray[i] as Done
                                _elimReceiverArray[i] = null
                                incElimReceiverArraySize(1)
                                _elimsSucc++
                                return done.value
                            }
                        }
                    }
                }
            }
            // Elimination was unsuccessful :(
            decElimSenderArraySize(1)
        }

        return null
    }

    // Tries to do elimination. Returns a result on success, `null` if fails.
    private fun tryEliminate(element: Any): Any? {
        if (element == RECEIVER_ELEMENT) {
            return tryEliminateReceiver()
        } else {
            return tryEliminateSender(element)
        }
    }

    // This method is based on `#sendOrReceiveSuspend`. Returns `null` if fails.
    private fun <T> sendOrReceiveNonSuspend(element: Any): T? {
        try_again@ while (true) { // CAS loop
            val res = tryEliminate(element)
            if (res != null) return res as T
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
                    // Queue is empty, try to do elimination
                    // and return `null` if it fails.
                    return null
                } else {
                    // Queue is empty, try to do elimination
                    // and return `null` if it fails.
                    return null
                }
            } else {
                // The waiting queue is not empty and it is guaranteed that `headDeqIdx < headEnqIdx`.
                // Try to remove the opposite continuation (a sender for a receiver or a receiver for a sender)
                // if waiting queue stores such in the `head` node at `headDeqIdx` index.
                var firstElement = readElement(head, headDeqIdx)
                if (firstElement == TAKEN_ELEMENT) {
                    // Try to move the deque index in the `head` node
                    deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                    incElimReceiverArraySize(1); incElimSenderArraySize(1)
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
                                if (!adjustHead(head)) { incElimReceiverArraySize(1); incElimSenderArraySize(1); continue@try_again }
                                continue@read_state
                            }
                            // Check that `(head.id, headDeqIdx) < (headIdLimit, headDeqIdxLimit)`
                            // and re-start the whole operation if needed
                            if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit)) { incElimReceiverArraySize(1); incElimSenderArraySize(1); continue@try_again }
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
                    return null
                }
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

    fun sendSpin(element: E) = sendOrReceivePark<Unit>(element!!)
    fun receiveSpin(): E = sendOrReceivePark(RECEIVER_ELEMENT)

    fun <T> sendOrReceivePark(element: Any): T {
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
                    if (adjustHead(head)) {
                        continue@try_again
                    }
                    // Queue is empty, try to add a new node with the current continuation.
                    val newNode = addNewNodeWithCurrentThread(tail, element)
                    if (newNode != null) {
                        return waitOnCell(newNode, 0) as T
                    }
                } else {
                    // The `head` node is not full, therefore the waiting queue
                    // is empty. Try to add the current continuation to the queue.
                    if (storeCurrentThread(head, headEnqIdx, element)) {
                        return waitOnCell(head, headEnqIdx) as T
                    }
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
                    incElimReceiverArraySize(1); incElimSenderArraySize(1)
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
                        if (tryResumeThread(head, headDeqIdx, element)) {
                            // The rendezvous is happened, congratulations!
                            // Resume the current continuation
                            val result = (if (element == RECEIVER_ELEMENT) firstElement else Unit) as T
                            return result
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
                            if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit)) {
                                continue@try_again
                            }
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
                    read_state@ while (true) {
                        // Try to add a new node with the current continuation and element
                        // if the tail is full, otherwise try to store it at the `tailEnqIdx` index.
                        if (tailEnqIdx == segmentSize)  {
                            val newNode = addNewNodeWithCurrentThread(tail, element)
                            if (newNode != null) {
                                return waitOnCell(newNode, 0) as T
                            }
                        } else {
                            if (storeCurrentThread(tail, tailEnqIdx, element)) {
                                return waitOnCell(tail, tailEnqIdx) as T
                            }
                        }
                        // Re-read the required pointers. Read tail and its indexes at first
                        // and only then head with its indexes.
                        tail = _tail
                        tailEnqIdx = tail._enqIdx
                        head = _head
                        headDeqIdx = head._deqIdx
                        if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit)) {
                            continue@try_again
                        }
                    }
                }
            }
        }
    }

    private fun addNewNodeWithCurrentThread(tail: Node, element: Any): Node? {
        // If next node is not null, help to move the tail pointer
        val tailNext = tail._next
        if (tailNext != null) {
            // If this CAS fails, another thread moved the tail pointer
            tailUpdater.compareAndSet(this, tail, tailNext)
            return null
        }
        // Create a new node with this continuation and element and try to add it
        val node = Node(segmentSize, tail.id + 1, Thread.currentThread(), element)
        if (nextUpdater.compareAndSet(tail, null, node)) {
            // New node added, try to move tail,
            // if the CAS fails, another thread moved it.
            tailUpdater.compareAndSet(this, tail, node)
            return node
        } else {
            // Next node is not null, help to move the tail pointer
            tailUpdater.compareAndSet(this, tail, tail._next)
            return null
        }
    }

    private fun waitOnCell(node: Node, index: Int): Any {
        val t = Thread.currentThread()
        var res: Any? = null
        while (true) {
            res = node.getContinuationVolatile(index)
            if (res != t) break
//            LockSupport.park()
        }
        if (res == RECEIVER_ELEMENT) return Unit else return res!!
    }

    private fun storeCurrentThread(node: Node, index: Int, element: Any): Boolean {
        // Try to move enqueue index forward, return `false` if fails
        if (!enqIdxUpdater.compareAndSet(node, index, index + 1)) return false
        // Slot `index` is claimed, try to store the continuation and the element (in this order!) to it.
        // Can fail if another thread marked this slot as broken, return `false` in this case.
        node.putContinuationLazy(index, Thread.currentThread())
        if (node.casElement(index, null, element)) {
            // Setup the continuation before suspend
            return true
        } else {
            // The slot is broken, clean it and return `false`
            node.putContinuationLazy(index, TAKEN_CONTINUATION)
            return false
        }
    }

    private fun tryResumeThread(head: Node, dequeIndex: Int, element: Any): Boolean {
        // Try to move 'dequeIndex' forward, return `false` if fails
        if (deqIdxUpdater.compareAndSet(head, dequeIndex, dequeIndex + 1)) {
            // Get a continuation at the specified index
//            val cont = head._data[dequeIndex * 2 + 1] as CancellableContinuation<in Any>
            val thread = head.getContinuationWeak(dequeIndex) as Thread
            // Clear the slot to avoid memory leaks
//            head._data[dequeIndex * 2] = TAKEN_ELEMENT
//            head._data[dequeIndex * 2 + 1] = TAKEN_CONTINUATION
            head.putElementLazy(dequeIndex, TAKEN_ELEMENT)
            head.putContinuationVolatile(dequeIndex, element)
            // Try to resume the continuation
//            LockSupport.unpark(thread)
            return true
        } else return false
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
        @JvmField val ELIM_MAX_ARR_SIZE = Runtime.getRuntime().availableProcessors() * 4
        @JvmField val ELIM_CLAIMED = Symbol("ELIM_CLAIMED")
        @JvmField val ELIM_SENDER_DONE = Symbol("ELIM_SENDER_DONE")
        @JvmField val ELIM_RECEIVER_ELEMENT = Symbol("ELIM_RECEIVER_ELEMENT")

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