package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.suspendAtomicCancellableCoroutine
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

class RendezvousChannelKovalMSQueue<E> : ChannelKoval<E> {

    abstract class Node(@JvmField @Volatile var _next: Node? = null)

    class SenderNode(@JvmField val cont: CancellableContinuation<Unit>, @JvmField val element: Any) : Node()
    class ReceiverNode(@JvmField val cont: CancellableContinuation<*>) : Node()

    @Volatile
    private var _head: Node
    @Volatile
    private var _tail: Node

    init {
        val sentinel = object : Node(){}
        _head = sentinel
        _tail = sentinel
    }

    suspend override fun send(element: E) {
        if (offer(element)) return // fast path
        sendSuspend(element!!)
    }

    suspend override fun receive(): E {
        return poll() ?: receiveSuspend()
    }

    private suspend fun receiveSuspend() = suspendAtomicCancellableCoroutine<E>(holdCancellability = true) sc@ { curCont ->
        while (true) {
            val tail = _tail
            val head = _head
            val headNext = head._next
            if (headNext is SenderNode) {
                // Queue contains senders, try to remove the first one
                if (headUpdater.compareAndSet(this, head, headNext)) {
                    val cont = headNext.cont
                    val token = cont.tryResume(Unit)
                    if (token != null) {
                        cont.completeResume(token)
                        curCont.resume(headNext.element as E)
                        return@sc
                    } else {
                        // TODO cancellation
                    }
                }
            } else {
                // Queue is empty or contains receivers,
                // try to add the current continuation to the queue
                val tailNext = tail._next
                if (tailNext != null) {
                    tailUpdater.compareAndSet(this, tail, tailNext)
                } else {
                    val node = ReceiverNode(curCont)
                    if (nextUpdater.compareAndSet(tail, null, node)) {
                        tailUpdater.compareAndSet(this, tail, node)
                        curCont.initCancellability()
                        curCont.invokeOnCompletion { /* TODO cancellation */ }
                        return@sc
                    }
                }
            }
        }
    }

    private suspend fun sendSuspend(element: E) = suspendAtomicCancellableCoroutine<Unit>(holdCancellability = true) sc@ { curCont ->
        while (true) {
            val tail = _tail
            val tailNext = tail._next
            val head = _head
            val headNext = head._next
            if (headNext is ReceiverNode) {
                // Queue contains receivers, try to remove it
                if (headUpdater.compareAndSet(this, head, headNext)) {
                    val cont = headNext.cont as CancellableContinuation<E>
                    val token = cont.tryResume(element)
                    if (token != null) {
                        cont.completeResume(token)
                        curCont.resume(Unit)
                        return@sc
                    } else {
                        // TODO cancellation
                    }
                }
            } else {
                // Queue is empty or contains senders,
                // try to add the current continuation to the queue
                if (tailNext != null) {
                    tailUpdater.compareAndSet(this, tail, tailNext)
                } else {
                    val node = SenderNode(curCont, element!!)
                    if (nextUpdater.compareAndSet(tail, null, node)) {
                        tailUpdater.compareAndSet(this, tail, node)
                        curCont.initCancellability()
                        curCont.invokeOnCompletion { /* TODO cancellation */ }
                        return@sc
                    }
                }
            }
        }
    }

    override fun offer(element: E): Boolean {
        while (true) {
            val head = _head
            val headNext = head._next
            if (headNext is ReceiverNode) {
                // Queue contains receivers, try to remove it
                if (headUpdater.compareAndSet(this, head, headNext)) {
                    val cont = headNext.cont as CancellableContinuation<E>
                    val token = cont.tryResume(element)
                    if (token != null) {
                        cont.completeResume(token)
                        return true
                    } else {
                        // TODO cancellation
                    }
                }
            } else return false
        }
    }

    override fun poll(): E? {
        while (true) {
            val head = _head
            val headNext = head._next
            if (headNext is SenderNode) {
                // Queue contains senders, try to remove the first one
                if (headUpdater.compareAndSet(this, head, headNext)) {
                    val cont = headNext.cont
                    val token = cont.tryResume(Unit)
                    if (token != null) {
                        cont.completeResume(token)
                        return headNext.element as E
                    } else {
                        // TODO cancellation
                    }
                }
            } else return null
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
        @JvmField
        val tailUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKovalMSQueue::class.java, Node::class.java, "_tail")
        @JvmField
        val headUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKovalMSQueue::class.java, Node::class.java, "_head")
        @JvmField
        val nextUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Node::class.java, "_next")
    }
}

private val RECEIVER_ELEMENT = Symbol("RECEIVER_ELEMENT")