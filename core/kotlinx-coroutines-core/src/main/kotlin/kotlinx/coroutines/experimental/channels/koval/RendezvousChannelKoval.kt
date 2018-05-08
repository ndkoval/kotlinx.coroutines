package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.suspendAtomicCancellableCoroutine
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

// See the original [RendezvousChannel] class for the contract details.
// Only implementation details are documented here.
class RendezvousChannelKoval<E>(
        private val segmentSize: Int = 64
): ChannelKoval<E> {
    // Waiting queue node
    private class Node(val id: Int, segmentSize: Int) {
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
        private val _data = arrayOfNulls<Any?>(segmentSize)

        constructor(id: Int, segmentSize: Int, cont: Any) : this(id, segmentSize) {
            _enqIdx = 1
            _data[0] = cont
        }

        inline fun casContinuation(index: Int, expect: Any?, update: Any): Boolean {
            return UNSAFE.compareAndSwapObject(_data, byteOffset(index), expect, update)
        }

        inline fun putContinuationLazy(index: Int, cont: Any?) {
            UNSAFE.putOrderedObject(_data, byteOffset(index), cont)
        }

        inline fun putContinuationVolatile(index: Int, cont: Any?) {
            UNSAFE.putObjectVolatile(_data, byteOffset(index), cont)
        }

        inline fun getContinuationWeak(index: Int): Any? {
            return UNSAFE.getObject(_data, byteOffset(index))
        }

        inline fun getContinuationVolatile(index: Int): Any? {
            return UNSAFE.getObjectVolatile(_data, byteOffset(index))
        }
    }

    private class Receiver(val cont: CancellableContinuation<*>)

    // These head and tail nodes are managed similar to MS queue.
    // For CAS operations [headUpdater] and [tailUpdater] are used.
    @Volatile private var _head: Node
    @Volatile private var _tail: Node

    init {
        // Initialize queue with empty node similar to MS queue
        // algorithm, but this node is just empty, not sentinel.
        val emptyNode = Node(0, segmentSize)
        _head = emptyNode
        _tail = emptyNode
    }

    override suspend fun send(element: E) {
        // Try to send without suspending at first,
        // invoke suspend implementation if it is not succeed.
        if (offer(element)) return
        sendOrReceiveSuspend<Unit>(element)
    }

    override suspend fun receive(): E {
        // Try to send without suspending at first,
        // invoke suspend implementation if it is not succeed.
        return poll() ?: sendOrReceiveSuspend(RECEIVER_ELEMENT)
    }

    // Main function in this chanel, which implements both `#send` and `#receive` operations.
    // Note that `#offer` and `#poll` functions are just simplified versions of this one.
    private suspend fun <T> sendOrReceiveSuspend(element: Any?) = suspendAtomicCancellableCoroutine<T>(holdCancellability = true) sc@ { curCont ->
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
            // If `headDeqIdx` is equals to the segment size the head node is full.
            // Try to move `_head` pointer forward at first and add a new node
            // with the current continuation if it fails.
            if (headDeqIdx == segmentSize) {
                // Try to move `_head` pointer forward and start the operation again.
                if (adjustHead(head)) continue@try_again
                // Queue is empty, try to add a new node with the current continuation.
                if (addNewNode(head, curCont, element)) return@sc
                // `head._next` is non-empty, retry the operation.
                continue@try_again
            }
            // It is guaranteed that `headDeqIdx < segmentSize`. If this cell is empty,
            // queue is empty too. Store the current continuation to the queue in this case.
            var firstCont = head.getContinuationVolatile(headDeqIdx)
            if (firstCont == null) {
                // Queue is empty, try to add the current continuation to the queue.
                if (storeContinuation(head, headDeqIdx, curCont, element)) return@sc
                // Cannot store the continuation -- queue is not empty,
                // try the operation again
                continue@try_again
            }
            if (firstCont == TAKEN_CONTINUATION) {
                deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                continue@try_again
            }
            // Queue is not empty
            val isFirstContReceiver = firstCont is Receiver
            val makeRendezvous = if (element == RECEIVER_ELEMENT) !isFirstContReceiver else isFirstContReceiver
            val headIdLimit = tail.id
            val headDeqIdxLimit = tailEnqIdx
            if (makeRendezvous) {
                while (true) {
                    if (firstCont == TAKEN_CONTINUATION) {
                        deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                    } else {
                        if (element != RECEIVER_ELEMENT)
                            firstCont = (firstCont as Receiver).cont
                        if (tryResumeContinuation(head, headDeqIdx, firstCont!!, element)) {
                            // The rendezvous is happened, congratulations!
                            // Resume the current continuation
                            firstCont as CancellableContinuation<*>
                            val result = (if (element == RECEIVER_ELEMENT) firstCont.data else Unit) as T
                            curCont.resume(result)
                            return@sc
                        }
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
                        if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit))
                            continue@try_again
                        // Re-read the first element
                        firstCont = head.getContinuationVolatile(headDeqIdx)
                        break@read_state
                    }
                }
            } else {
                read_state@ while (true) {
                    // Try to add a new node with the current continuation and element
                    // if the tail is full, otherwise try to store it at the `tailEnqIdx` index.
                    if (tailEnqIdx == segmentSize) {
                        if (tail == _tail)
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
                    if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit))
                        continue@try_again
                }
            }
        }
    }

    // This method is based on `#sendOrReceiveSuspend`. Returns `null` if fails.
    private fun <T> sendOrReceiveNonSuspend(element: Any): T? {
        try_again@ while (true) { // CAS loop
            // Read the tail and its enqueue index at first, then the head and its indexes.
            // It is important to read tail and its index at first. If algorithm
            // realizes that the same continuations (senders or receivers) are stored
            // in the waiting queue, it can add the current continuation to the already
            // read tail index if it is not changed. In this case, it is guaranteed that
            // the waiting queue still has the same continuations or is empty.
            val tail = _tail
            val tailEnqIdx = tail._enqIdx
            var head = _head
            var headDeqIdx = head._deqIdx
            // If `headDeqIdx` is equals to the segment size the head node is full.
            // Try to move `_head` pointer forward at first and add a new node
            // with the current continuation if it fails.
            if (headDeqIdx == segmentSize) {
                // Try to move `_head` pointer forward and start the operation again.
                if (adjustHead(head)) continue@try_again
                // Queue is empty, try to add a new node with the current continuation.
                return null
            }
            // It is guaranteed that `headDeqIdx < segmentSize`. If this cell is empty,
            // queue is empty too. Store the current continuation to the queue in this case.
            var firstCont = head.getContinuationVolatile(headDeqIdx)
            if (firstCont == null) return null
            if (firstCont == TAKEN_CONTINUATION) {
                deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                continue@try_again
            }
            // Queue is not empty
            val isFirstContReceiver = firstCont is Receiver
            val makeRendezvous = if (element == RECEIVER_ELEMENT) !isFirstContReceiver else isFirstContReceiver
            val headIdLimit = tail.id
            val headDeqIdxLimit = tailEnqIdx
            if (makeRendezvous) {
                while (true) {
                    if (firstCont == TAKEN_CONTINUATION) {
                        deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                    } else {
                        if (element != RECEIVER_ELEMENT)
                            firstCont = (firstCont as Receiver).cont
                        else
                            firstCont as CancellableContinuation<*>
                        if (tryResumeContinuation(head, headDeqIdx, firstCont, element)) {
                            // The rendezvous is happened, congratulations!
                            // Resume the current continuation
                            val result = (if (element == RECEIVER_ELEMENT) firstCont.data else Unit) as T
                            return result
                        }
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
                        if (head.id > headIdLimit || (head.id == headIdLimit && headDeqIdx >= headDeqIdxLimit))
                            continue@try_again
                        // Re-read the first element
                        firstCont = head.getContinuationVolatile(headDeqIdx)
                        break@read_state
                    }
                }
            } else return null
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
    private fun addNewNode(curTail: Node, cont: CancellableContinuation<*>, element: Any?): Boolean {
        // If next node is not null, help to move the tail pointer
        val curTailNext = curTail._next
        if (curTail != _tail) return false
        if (curTailNext != null) {
            // If this CAS fails, another thread moved the tail pointer
            tailUpdater.compareAndSet(this, curTail, curTailNext)
            return false
        }
        // Create a new node with this continuation and element and try to add it
        val contBox = if (element == RECEIVER_ELEMENT) Receiver(cont) else cont
        cont.data = element
        val node = Node(curTail.id + 1, segmentSize, contBox)
        if (nextUpdater.compareAndSet(curTail, null, node)) {
            // New node added, try to move tail,
            // if the CAS fails, another thread moved it.
            tailUpdater.compareAndSet(this, curTail, node)
            // Setup the continuation before suspend
            cont.initCancellability()
            cont.invokeOnCompletion { onCancel(curTail, 0) }
            return true
        } else {
            // Next node is not null, help to move the tail pointer
            tailUpdater.compareAndSet(this, curTail, curTail._next)
            cont.data = null
            return false
        }
    }

    // Try to remove a continuation from the specified node at the
    // specified index and resume it. Returns `true` on success, `false` otherwise.
    private fun tryResumeContinuation(head: Node, dequeIndex: Int, cont: Any, curElement: Any?): Boolean {
        // Try to move 'dequeIndex' forward, return `false` if fails
        if (head._deqIdx != dequeIndex) return false
        if (deqIdxUpdater.compareAndSet(head, dequeIndex, dequeIndex + 1)) {
            // Clear the slot to avoid memory leaks
            head.putContinuationLazy(dequeIndex, TAKEN_CONTINUATION)
            // Try to resume the continuation
            val value = if (curElement == RECEIVER_ELEMENT) Unit else curElement
            cont as CancellableContinuation<in Any?>
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
    private fun storeContinuation(node: Node, index: Int, cont: CancellableContinuation<*>, element: Any?): Boolean {
        if (node._enqIdx != index) return false
        val contByIndex = node.getContinuationVolatile(index)
        if (contByIndex != null) {
            enqIdxUpdater.compareAndSet(node, index, index + 1)
            return false
        }
        val contBox = if (element == RECEIVER_ELEMENT) Receiver(cont) else cont
        cont.data = element
        // Try to claim the slot
        if (node.casContinuation(index, null, contBox)) {
            // Move enqueue index forward (OK if fails)
            enqIdxUpdater.compareAndSet(node, index, index + 1)
            // Setup the continuation before suspend
            cont.initCancellability()
            cont.invokeOnCompletion { onCancel(node, index) }
            return true
        } else {
//            enqIdxUpdater.compareAndSet(node, index, index + 1)
            cont.data = null
            return false
        }
    }

    // This method is invoked when a continuation stored in
    // the specified node by the specified index is cancelled.
    private fun onCancel(node: Node, index: Int) {
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
        @JvmField val UNSAFE = UtilUnsafe.unsafe
        @JvmField val base = UNSAFE.arrayBaseOffset(Array<Any>::class.java)
        @JvmField val shift = 31 - Integer.numberOfLeadingZeros(UNSAFE.arrayIndexScale(Array<Any>::class.java))
        @JvmStatic inline fun byteOffset(i: Int) = (i.toLong() shl shift) + base

        @JvmField val TAKEN_CONTINUATION = Symbol("TAKEN_CONTINUATION")
        @JvmField val RECEIVER_ELEMENT = Symbol("RECEIVER_ELEMENT")

        @JvmField val tailUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKoval::class.java, Node::class.java, "_tail")
        @JvmField val headUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKoval::class.java, Node::class.java, "_head")
        @JvmField val nextUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Node::class.java, "_next")

        @JvmField val deqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_deqIdx")
        @JvmField val enqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_enqIdx")
    }
}