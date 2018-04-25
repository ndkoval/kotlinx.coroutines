package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import sun.misc.Contended
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater


fun main(args: Array<String>) = runBlocking {
    val ch = RendezvousChannelKoval<Int>()
    val jobs = List(100000) {id ->
        launch {
            if (id % 2 == 0) {
                println("$id: sending")
                ch.send(id)
                println("$id: sent")
            } else {
                delay(1000)
                println("$id: receiving")
                val x = ch.receive()
                println("$id: received $x")
            }
        }
    }
    jobs.forEach { it.join() }
}


class RendezvousChannelKoval<E>(private val segmentSize: Int = 32): ChannelKoval<E> {
    private class Node<E>(segmentSize: Int) {
        @Volatile @JvmField var _deqIdx = 0
        @Volatile @JvmField var _enqIdx = 0

        @Volatile @JvmField var _next: Node<E>? = null

        @JvmField val _data = AtomicReferenceArray<Any?>(segmentSize * 2)
    }

    @Contended @Volatile
    private var _head: Node<E>

    @Contended @Volatile
    private var _tail: Node<E>

    init {
        val sentinelNode = Node<E>(segmentSize)
        _head = sentinelNode
        _tail = sentinelNode
    }

    suspend override fun send(element: E) {
        if (offer(element)) return // fast path
        sendSuspend(element)
    }

    private suspend fun sendSuspend(element: E) = suspendAtomicCancellableCoroutine<Unit>(holdCancellability = true) sc@ { curCont ->
        while (true) { // CAS-loop
            val head = _head
            val headDeqIdx = head._deqIdx
            val headEnqIdx = head._enqIdx

            val tail = _tail
            val tailDeqIdx = tail._deqIdx
            val tailEnqIdx = tail._enqIdx

            if (headDeqIdx == headEnqIdx) {
                // Queue is empty or _head should be moved forward (CAS to _next)
                if (headDeqIdx == segmentSize) {
                    // Segment is full, move _next forward
                    val headNext = head._next
                    if (headNext != null) {
                        headUpdater.compareAndSet(this, head, headNext)
                    } else {
                        // Queue is empty, add a new segment
                        check(head == tail, { "Queue is empty, head and tail should point to the same node" })
                        // Create new segment and add it as 'tail._next'
                        val node = Node<E>(segmentSize)
                        if (nextUpdater.compareAndSet(tail, null, node)) {
                            // New segment is added successfully, help to move tail
                            tailUpdater.compareAndSet(this, tail, node)
                        }
                    }
                } else {
                    check(head == tail, { "Queue has one segment only" })
                    // Try to add sender to the waiting queue at index 'headEnqIdx'
                    if (enqIdxUpdater.compareAndSet(head, headEnqIdx, headEnqIdx + 1)) {
                        head._data[headEnqIdx * 2 + 1] = curCont
                        head._data[headEnqIdx * 2] = element
                        curCont.initCancellability()
                        return@sc
                    }
                }
            } else {
                // Queue is not empty. Remove receiver from the head
                // or add the sender to the tail.
                var headValue = head._data[headDeqIdx * 2]
                // Spin wait until value is set  <--- TODO This makes algorithm blocking.
                                                   // TODO It is possible to make CAS: null -> TAKEN_VALUE so
                                                   // TODO we'll have a progress, but it requires CAS on set operation
                                                   // TODO too and guarantees obstruction-freedom only.
                while (headValue == null) headValue = head._data[headDeqIdx * 2]
                // Check if the value is receiver specific or not
                if (headValue == RECEIVER_VALUE) {
                    // Try to remove receiver
                    if (deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)) {
                        val receiverCont = head._data[headDeqIdx * 2 + 1] as CancellableContinuation<E>
                        head._data[headDeqIdx * 2 + 1] = TAKEN_CONTINUATION
                        // Try to resume receiver
                        val token = receiverCont.tryResume(element)
                        if (token != null) {
                            // Receiver is going to be resumed successfully
                            receiverCont.completeResume(token)
                            // Resume us as well
                            curCont.resume(Unit)
                            return@sc
                        }
                    }
                } else {
                    // The sender should be added
                    // Check if tail segment is full and create a new node if needed
                    if (tailEnqIdx == segmentSize) {
                        val tailNext = tail._next
                        if (tailNext != null) {
                            // Help with tail moving
                            tailUpdater.compareAndSet(this, tail, tailNext)
                        } else {
                            // Create new segment and add it as 'tail._next'
                            val node = Node<E>(segmentSize)
                            if (nextUpdater.compareAndSet(tail, null, node)) {
                                // New segment is added successfully, help to move tail
                                tailUpdater.compareAndSet(this, tail, node)
                            }
                        }
                    } else {
                        if (enqIdxUpdater.compareAndSet(tail, tailEnqIdx, tailEnqIdx + 1)) {
                            // Add sender to the claimed slot
                            tail._data[tailEnqIdx * 2 + 1] = curCont
                            tail._data[tailEnqIdx * 2] = element
                            curCont.initCancellability()
                            return@sc
                        }
                    }
                }
            }
        }
    }

    override fun offer(element: E): Boolean {
        return false
    }

    suspend override fun receive(): E = suspendAtomicCancellableCoroutine<E>(holdCancellability = true) sc@ { curCont ->
        while (true) { // CAS-loop
            val head = _head
            val headDeqIdx = head._deqIdx
            val headEnqIdx = head._enqIdx

            val tail = _tail
            val tailDeqIdx = tail._deqIdx
            val tailEnqIdx = tail._enqIdx

            if (headDeqIdx == headEnqIdx) {
                // Queue is empty or _head should be moved forward (CAS to _next)
                if (headDeqIdx == segmentSize) {
                    // Segment is full, move _next forward
                    val headNext = head._next
                    if (headNext != null) {
                        headUpdater.compareAndSet(this, head, headNext)
                    } else {
                        // Queue is empty, add a new segment
                        check(head == tail, { "Queue is empty, head and tail should point to the same node" })
                        // Create new segment and add it as 'tail._next'
                        val node = Node<E>(segmentSize)
                        if (nextUpdater.compareAndSet(tail, null, node)) {
                            // New segment is added successfully, help to move tail
                            tailUpdater.compareAndSet(this, tail, node)
                        }
                    }
                } else {
                    check(head == tail, { "Queue has one segment only" })
                    // Try to add receiver to the waiting queue at index 'headEnqIdx'
                    if (enqIdxUpdater.compareAndSet(head, headEnqIdx, headEnqIdx + 1)) {
                        head._data[headEnqIdx * 2 + 1] = curCont
                        head._data[headEnqIdx * 2] = RECEIVER_VALUE
                        curCont.initCancellability()
                        return@sc
                    }
                }
            } else {
                // Queue is not empty. Remove receiver from the head
                // or add the sender to the tail.
                var headValue = head._data[headDeqIdx * 2]
                // Spin wait until value is set  <--- TODO This makes algorithm blocking.
                // TODO It is possible to make CAS: null -> TAKEN_VALUE so
                // TODO we'll have a progress, but it requires CAS on set operation
                // TODO too and guarantees obstruction-freedom only.
                while (headValue == null) headValue = head._data[headDeqIdx * 2]
                // Check if the value is receiver specific or not
                if (headValue != RECEIVER_VALUE) {
                    // Try to remove receiver
                    if (deqIdxUpdater.compareAndSet(head, headDeqIdx, headDeqIdx + 1)) {
                        val sender = head._data[headDeqIdx * 2 + 1] as CancellableContinuation<Unit>
                        val element = head._data[headDeqIdx * 2] as E
                        head._data[headDeqIdx * 2 + 1] = TAKEN_CONTINUATION
                        // Try to resume receiver
                        val token = sender.tryResume(Unit)
                        if (token != null) {
                            // Receiver is going to be resumed successfully
                            sender.completeResume(token)
                            // Resume us as well
                            curCont.resume(element)
                            return@sc
                        }
                    }
                } else {
                    // The sender should be added
                    // Check if tail segment is full and create a new node if needed
                    if (tailEnqIdx == segmentSize) {
                        val tailNext = tail._next
                        if (tailNext != null) {
                            // Help with tail moving
                            tailUpdater.compareAndSet(this, tail, tailNext)
                        } else {
                            // Create new segment and add it as 'tail._next'
                            val node = Node<E>(segmentSize)
                            if (nextUpdater.compareAndSet(tail, null, node)) {
                                // New segment is added successfully, help to move tail
                                tailUpdater.compareAndSet(this, tail, node)
                            }
                        }
                    } else {
                        if (enqIdxUpdater.compareAndSet(tail, tailEnqIdx, tailEnqIdx + 1)) {
                            // Add receiver to the claimed slot
                            tail._data[tailEnqIdx * 2 + 1] = curCont
                            tail._data[tailEnqIdx * 2] = RECEIVER_VALUE
                            curCont.initCancellability()
                            return@sc
                        }
                    }
                }
            }
        }
    }

    override fun poll(): E? {
        return null
    }

    override fun close(cause: Throwable?): Boolean {
        TODO("not implemented")
    }

    override val onSend: SelectClause2<E, ChannelKoval<E>>
        get() = TODO("not implemented")

    override val onReceive: SelectClause1<E>
        get() = TODO("not implemented")


    private companion object {
        @JvmField
        val tailUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKoval::class.java, Node::class.java, "_tail")
        @JvmField
        val headUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKoval::class.java, Node::class.java, "_head")
        @JvmField
        val nextUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Node::class.java, "_next")

        @JvmField
        val deqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_deqIdx")
        @JvmField
        val enqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_enqIdx")
    }
}

private val TAKEN_VALUE = Symbol("TAKEN_VALUE")
private val TAKEN_CONTINUATION = Symbol("TAKEN_CONTINUATION")
private val RECEIVER_VALUE = Symbol("RECEIVER_VALUE")





class RendezvousChannelElizarov<E> : ChannelKoval<E> {
    private val c = RendezvousChannel<E>()

    suspend override fun send(element: E) = c.send(element)
    override fun offer(element: E): Boolean = c.offer(element)

    suspend override fun receive(): E = c.receive()
    override fun poll(): E? = c.poll()

    override fun close(cause: Throwable?): Boolean {
        TODO("not implemented")
    }

    override val onReceive: SelectClause1<E>
        get() = TODO("not implemented")

    override val onSend: SelectClause2<E, ChannelKoval<E>>
        get() = TODO("not implemented")
}