package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

// See the original [RendezvousChannel] class for the contract details.
// Only implementation details are documented here.
class RendezvousChannelKoval<E>(
        private val segmentSize: Int = 32,
        private val spinThreshold: Int = 300
): ChannelKoval<E> {
    // Waiting queue node
    private class Node(segmentSize: Int) {
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
        @JvmField val _data = AtomicReferenceArray<Any?>(segmentSize * 2)

        constructor(segmentSize: Int, cont: CancellableContinuation<*>, element: Any) : this(segmentSize) {
            _enqIdx = 1
            _data[1] = cont
            _data[0] = element
        }
    }

    // These head and tail nodes are managed similar to MS queue.
    // For CAS operations [headUpdater] and [tailUpdater] are used.
    @Volatile private var _head: Node
    @Volatile private var _tail: Node

    init {
        // Initialize queue with empty node similar to MS queue
        // algorithm, but this node is just empty, not sentinel.
        val emptyNode = Node(segmentSize)
        _head = emptyNode
        _tail = emptyNode
    }

    override suspend fun send(element: E) {
        // Try to send without suspending at first,
        // invoke suspend implementation if it is not succeed.
//        if (offer(element)) return
        sendOrReceiveSuspend<Unit>(element!!)
    }

    override suspend fun receive(): E {
        // Try to send without suspending at first,
        // invoke suspend implementation if it is not succeed.
        return /*poll() ?:*/ sendOrReceiveSuspend(RECEIVER_ELEMENT)
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
            val tail = _tail
            val tailEnqIdx = tail._enqIdx
            val head = _head
            val headDeqIdx = head._deqIdx
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
                val firstElement = readElement(head, headDeqIdx)
                if (firstElement == TAKEN_ELEMENT) {
                    // Try to move the deque index in the `head` node
                    deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                    continue@try_again
                }
                // The `firstElement` is either sender or receiver. Check if a rendezvous is possible
                // and try to remove the first element in this case, try to add the current
                // continuation to the waiting queue otherwise.
                val makeRendezvous = if (element == RECEIVER_ELEMENT) firstElement != RECEIVER_ELEMENT else firstElement == RECEIVER_ELEMENT
                if (makeRendezvous) {
                    if (tryResumeContinuation(head, headDeqIdx, element)) {
                        // The rendezvous is happened, congratulations!
                        // Resume the current continuation
                        val result = (if (element == RECEIVER_ELEMENT) firstElement else Unit) as T
                        curCont.resume(result)
                        return@sc
                    }
                } else {
                    // Try to add a new node with the current continuation and element
                    // if the tail is full, otherwise try to store it at the `tailEnqIdx` index.
                    if (tailEnqIdx == segmentSize) {
                        if (addNewNode(tail, curCont, element)) return@sc
                    } else {
                        if (storeContinuation(tail, tailEnqIdx, curCont, element)) return@sc
                    }
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
        val node = Node(segmentSize, cont, element)
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
        val i = index * 2
        // Spin wait on the slot
        var element = node._data[i]
        var attempt = 0
        do {
            if (element != null) return element
            element = node._data[i]
            attempt++
        } while (attempt < spinThreshold)
        // Cannot spin forever, mark the slot as broken if it is still unavailable
        if (node._data.compareAndSet(i, null, TAKEN_ELEMENT)) {
            return TAKEN_ELEMENT
        } else {
            // The element is set, read it and return
            return node._data[i]!!
        }
    }

    // Try to remove a continuation from the specified node at the
    // specified index and resume it. Returns `true` on success, `false` otherwise.
    private fun tryResumeContinuation(head: Node, dequeIndex: Int, element: Any): Boolean {
        // Try to move 'dequeIndex' forward, return `false` if fails
        if (deqIdxUpdater.compareAndSet(head, dequeIndex, dequeIndex + 1)) {
            // Get a continuation at the specified index
            val cont = head._data[dequeIndex * 2 + 1] as CancellableContinuation<in Any>
            // Clear the slot to avoid memory leaks
            head._data[dequeIndex * 2] = TAKEN_ELEMENT
            head._data[dequeIndex * 2 + 1] = TAKEN_CONTINUATION
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

    // This method is invoked when a continuation stored in
    // the specified node by the specified index is cancelled.
    private fun onCancel(node: Node, index: Int) {
        // TODO cancellation
    }

    override fun offer(element: E): Boolean {
        while (true) { // CAS loop
            // Read tail and its enqueue index at first, then head and its indexes
            val head = _head
            val headDeqIdx = head._deqIdx
            val headEnqIdx = head._enqIdx
            // If the waiting queue is empty, 'headDeqIdx == headEnqIdx'.
            // But they also could be equal if the 'head' node is full.
            if (headDeqIdx == headEnqIdx) {
                // Check if the node is full
                if (headDeqIdx == segmentSize) {
                    // 'head' node is full, try to move '_head' pointer forward and create a new node if needed
                    val headNext = head._next
                    if (headNext != null) {
                        // Move '_head' forward. If CAS fails, another thread moved it.
                        headUpdater.compareAndSet(this, head, headNext)
                    } else {
                        // Queue is empty, return 'false'
                        return false
                    }
                } else {
                    // Queue is empty, return 'false'
                    return false
                }
            } else {
                // Queue is not empty and 'headDeqIdx < headEnqIdx'.
                // Try to remove the required continuation if waiting queue contains required ones,
                // otherwise try to add the current one to the queue.
//                var firstElement = head._data[headDeqIdx * 2]
                // Spin wait until the element is set
                // TODO This spin wait makes the algorithm blocking. It is technically possible
                // TODO to change elements via CAS (null -> value) and instead of waiting here,
                // TODO make a CAS (null -> TAKEN_ELEMENT) in case null is seen.
//                while (firstElement == null) firstElement = head._data[headDeqIdx * 2]

                val firstElement = readElement(head, headDeqIdx)
                if (firstElement == TAKEN_ELEMENT) {
                    deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                    continue
                }

                // Check if the value is related to the required operation in order to make a rendezvous.
                val makeRendezvous = firstElement == RECEIVER_ELEMENT
                if (makeRendezvous) {
                    // Try to remove the continuation from 'headDeqIdx' position
                    if (deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)) {
                        // Get continuation
                        val cont = head._data[headDeqIdx * 2 + 1] as CancellableContinuation<in Any>
                        // Clear the slot to avoid memory leaks
                        head._data[headDeqIdx * 2 + 1] = TAKEN_CONTINUATION
                        head._data[headDeqIdx * 2] = TAKEN_ELEMENT
                        // Try to resume continuation
                        val token = cont.tryResume(element!!)
                        if (token != null) {
                            // The continuation is going to be resumed successfully
                            cont.completeResume(token)
                            // Rendezvous! Return 'true'
                            return true
                        } else {
                            // TODO coroutine has been cancelled, do something with that
                        }
                    }
                } else {
                   // The queue has senders, cannot be continued without suspend
                   return false
                }
            }
        }
    }

    override fun poll(): E? {
        while (true) { // CAS loop
            // Read tail and its enqueue index at first, then head and its indexes
            val head = _head
            val headDeqIdx = head._deqIdx
            val headEnqIdx = head._enqIdx
            // If the waiting queue is empty, 'headDeqIdx == headEnqIdx'.
            // But they also could be equal if the 'head' node is full.
            if (headDeqIdx == headEnqIdx) {
                // Check if the node is full
                if (headDeqIdx == segmentSize) {
                    // 'head' node is full, try to move '_head' pointer forward and create a new node if needed
                    val headNext = head._next
                    if (headNext != null) {
                        // Move '_head' forward. If CAS fails, another thread moved it.
                        headUpdater.compareAndSet(this, head, headNext)
                    } else {
                        // Queue is empty, return 'null'
                        return null
                    }
                } else {
                    // Queue is empty, return 'null'
                    return null
                }
            } else {
                // Queue is not empty and 'headDeqIdx < headEnqIdx'.
                // Try to remove the required continuation if waiting queue contains required ones,
                // otherwise try to add the current one to the queue.
//                var firstElement = head._data[headDeqIdx * 2]
                // Spin wait until the element is set
                // TODO This spin wait makes the algorithm blocking. It is technically possible
                // TODO to change elements via CAS (null -> value) and instead of waiting here,
                // TODO make a CAS (null -> TAKEN_ELEMENT) in case null is seen.
//                while (firstElement == null) firstElement = head._data[headDeqIdx * 2]
                // Check if the value is related to the required operation in order to make a rendezvous.

                val firstElement = readElement(head, headDeqIdx) ?: continue
                if (firstElement == TAKEN_ELEMENT) {
                    deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                    continue
                }

                val makeRendezvous = firstElement != RECEIVER_ELEMENT
                if (makeRendezvous) {
                    // Try to remove the continuation from 'headDeqIdx' position
                    if (deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)) {
                        // Get continuation
                        val cont = head._data[headDeqIdx * 2 + 1] as CancellableContinuation<in Any>
                        // Clear the slot to avoid memory leaks
                        head._data[headDeqIdx * 2 + 1] = TAKEN_CONTINUATION
                        head._data[headDeqIdx * 2] = TAKEN_ELEMENT
                        // Try to resume continuation
                        val token = cont.tryResume(Unit)
                        if (token != null) {
                            // The continuation is going to be resumed successfully
                            cont.completeResume(token)
                            // Rendezvous! Return removed element.
                            return firstElement as E
                        } else {
                            // TODO coroutine has been cancelled, do something with that
                        }
                    }
                } else {
                    // The queue has receivers, cannot be continued without suspend
                    return null
                }
            }
        }
    }

    // Tries to store the current continuation and element (in this order!)
    // to the specified node at the specified index. Returns `true` on success,
    // `false` otherwise`.
    private fun storeContinuation(node: Node, index: Int, cont: CancellableContinuation<*>, element: Any): Boolean {
        // Try to move enqueue index forward, return `false` if fails
        if (!enqIdxUpdater.compareAndSet(node, index, index + 1)) return false
        // Slot `index` is claimed, try to store the continuation and the element (in this order!) to it.
        // Can fail if another thread marked this slot as broken, return `false` in this case.
        node._data[index * 2 + 1] = cont
        if (node._data.compareAndSet(index * 2, null, element)) {
            // Setup the continuation before suspend
            cont.initCancellability()
            cont.invokeOnCompletion { onCancel(node, index) }
            return true
        } else {
            // The slot is broken, clean it and return `false`
            node._data[index * 2 + 1] = TAKEN_CONTINUATION
            return false
        }
    }

    override fun close(cause: Throwable?): Boolean {
        TODO("not implemented")
    }

    override val onSend: SelectClause2<E, ChannelKoval<E>>
        get() = TODO("not implemented")

    override val onReceive: SelectClause1<E>
        get() = TODO("not implemented")


    private companion object {
        @JvmField val tailUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKoval::class.java, Node::class.java, "_tail")
        @JvmField val headUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKoval::class.java, Node::class.java, "_head")
        @JvmField val nextUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Node::class.java, "_next")

        @JvmField val deqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_deqIdx")
        @JvmField val enqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_enqIdx")
    }
}

private val TAKEN_ELEMENT = Symbol("TAKEN_ELEMENT")
private val TAKEN_CONTINUATION = Symbol("TAKEN_CONTINUATION")
private val RECEIVER_ELEMENT = Symbol("RECEIVER_ELEMENT")