package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import sun.misc.Contended
import sun.misc.Unsafe
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

class RendezvousChannelKoval<E>(
        private val segmentSize: Int = 32,
        private val spinThreshold: Int = 500
): ChannelKoval<E> {
    private class Node(segmentSize: Int) {
        @JvmField @Volatile var _deqIdx = 0
        @JvmField @Volatile var _enqIdx = 0

        @JvmField @Volatile var _next: Node? = null

        @JvmField val _data = AtomicReferenceArray<Any?>(segmentSize * 2)
    }

    @Volatile private var _head: Node
    @Volatile private var _tail: Node

    init {
        val sentinelNode = Node(segmentSize)
        _head = sentinelNode
        _tail = sentinelNode
    }

    override suspend fun send(element: E) {
        if (offer(element)) return // fast path
        sendOrReceiveSuspend<Unit>(element!!)
    }

    override suspend fun receive(): E {
        return poll() ?: sendOrReceiveSuspend(RECEIVER_ELEMENT)
    }

    private suspend fun <T> sendOrReceiveSuspend(element: Any) = suspendAtomicCancellableCoroutine<T>(holdCancellability = true) sc@ { curCont ->
        while (true) { // CAS loop
            // Read tail and its enqueue index at first, then head and its indexes
            val tail = _tail
            val tailEnqIdx = tail._enqIdx
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
                        // Create new node. If CAS fails, another thread added a new one
                        val node = Node(segmentSize)
                        if (nextUpdater.compareAndSet(head, null, node)) {
                            // New node added, try to move tail. If this CAS fail, another thread moved it.
                            tailUpdater.compareAndSet(this, head, node)
                        }
                    }
                } else {
                    if (head != tail || headEnqIdx != tailEnqIdx) continue
                    // Queue is empty, try to add the current continuation
                    if (enqIdxUpdater.compareAndSet(tail, tailEnqIdx, tailEnqIdx + 1)) {
                        // Slot with 'headEnqIdx' index claimed, store the current continuation
                        if (storeContinuation(tail, tailEnqIdx, curCont, element))
                            return@sc
                    }
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

                val firstElement = readElement(head, headDeqIdx) ?: continue
                if (firstElement == TAKEN_ELEMENT) {
                    deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)
                    continue
                }

                // Check if the value is related to the required operation in order to make a rendezvous.
                // TODO maybe it is better to inline this function in order to get rid of this 'if' statement
                val makeRendezvous = if (element == RECEIVER_ELEMENT) firstElement != RECEIVER_ELEMENT else firstElement == RECEIVER_ELEMENT
                if (makeRendezvous) {
                    // Try to remove the continuation from 'headDeqIdx' position
                    if (deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)) {
                        // Get continuation
                        val cont = head._data[headDeqIdx * 2 + 1] as CancellableContinuation<in Any>
                        // Clear the slot to avoid memory leaks
                        head._data[headDeqIdx * 2 + 1] = TAKEN_CONTINUATION
                        head._data[headDeqIdx * 2] = TAKEN_ELEMENT
                        // Try to resume continuation
                        val value = if (element == RECEIVER_ELEMENT) Unit else element
                        val token = cont.tryResume(value)
                        if (token != null) {
                            // The continuation is going to be resumed successfully
                            cont.completeResume(token)
                            // Resume the current continuation as well
                            val curValue = (if (element == RECEIVER_ELEMENT) firstElement else Unit) as T
                            curCont.resume(curValue)
                            return@sc
                        } else {
                            // TODO coroutine has been cancelled, do something with that
                        }
                    }
                } else {
                    // Try to add the current continuation to the end of the queue.
                    // Move tail forward (and create a new node if needed)
                    // if the current tail segment is full.
                    if (tailEnqIdx == segmentSize) {
                        val tailNext = tail._next
                        if (tailNext != null) {
                            // Move tail forward. If this CAS fails, another thread moved it
                            tailUpdater.compareAndSet(this, tail, tailNext)
                        } else {
                            // Create new node. If CAS fails, another thread added a new one
                            val node = Node(segmentSize)
                            if (nextUpdater.compareAndSet(tail, null, node)) {
                                // Move tail forward. If this CAS fails, another thread moved it
                                tailUpdater.compareAndSet(this, tail, node)
                            }
                        }
                    } else {
                        // Add the current continuation to the 'tail'
                        if (enqIdxUpdater.compareAndSet(tail, tailEnqIdx, tailEnqIdx + 1)) {
                            // Slot with 'tailEnqIdx' index claimed, store the current continuation
                            if (storeContinuation(tail, tailEnqIdx, curCont, element))
                                return@sc
                        }
                    }
                }
            }
        }
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

    private fun readElement(node: Node, index: Int): Any? {
        val i = index * 2
        for (attempt in 1 .. spinThreshold) {
            val element = node._data[i]
            if (element != null) return element
        }
        if (node._data.compareAndSet(i, null, TAKEN_ELEMENT))
            return TAKEN_ELEMENT
        else
            return node._data[i]
    }

    private fun storeContinuation(node: Node, index: Int, cont: CancellableContinuation<*>, element: Any): Boolean {
        // Slot with 'tailEnqIdx' index claimed, add the continuation and the element (in this order!)
        node._data[index * 2 + 1] = cont
        if (node._data.compareAndSet(index * 2, null, element)) {
            // Init cancellability and suspend
            cont.initCancellability()
            cont.invokeOnCompletion { /* TODO cancellation */ }
            return true
        } else {
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