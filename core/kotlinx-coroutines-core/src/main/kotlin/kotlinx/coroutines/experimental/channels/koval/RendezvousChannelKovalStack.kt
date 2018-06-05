package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.suspendAtomicCancellableCoroutine
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

class RendezvousChannelKovalStack<E> : ChannelKoval<E> {
    sealed class Node(@JvmField val next: Node?) {
        class SenderNode(@JvmField val cont: CancellableContinuation<Unit>, @JvmField val element: Any, next: Node?) : Node(next)
        class ReceiverNode(@JvmField val cont: CancellableContinuation<*>, next: Node?) : Node(next)
    }

    @Volatile
    private var _head: Node? = null

    suspend override fun send(element: E) {
        if (offer(element)) return // fast path
        sendSuspend(element!!)
    }

    suspend override fun receive(): E {
        return poll() ?: receiveSuspend()
    }

    private suspend fun receiveSuspend() = suspendAtomicCancellableCoroutine<E>(holdCancellability = true) sc@ { curCont ->
        while (true) {
            val head = _head
            if (head is Node.SenderNode) {
                if (headUpdater.compareAndSet(this, head, head.next)) {
                    val sender = head.cont
                    val token = sender.tryResume(Unit)
                    if (token != null) {
                        sender.completeResume(token)
                        curCont.resume(head.element as E)
                        return@sc
                    } else {
                        // TODO cancellation
                    }
                }
            } else {
                val node = Node.ReceiverNode(curCont, head)
                if (headUpdater.compareAndSet(this, head, node)) {
                    curCont.initCancellability()
                    curCont.invokeOnCompletion { /* TODO cancellation */ }
                    return@sc
                }
            }
        }
    }

    private suspend fun sendSuspend(element: E) = suspendAtomicCancellableCoroutine<Unit>(holdCancellability = true) sc@ { curCont ->
        while (true) {
            val head = _head
            if (head is Node.ReceiverNode) {
                if (headUpdater.compareAndSet(this, head, head.next)) {
                    val receiver = head.cont as CancellableContinuation<E>
                    val token = receiver.tryResume(element)
                    if (token != null) {
                        receiver.completeResume(token)
                        curCont.resume(Unit)
                        return@sc
                    } else {
                        // TODO cancellation
                    }
                }
            } else {
                val node = Node.SenderNode(curCont, element!!, head)
                if (headUpdater.compareAndSet(this, head, node)) {
                    curCont.initCancellability()
                    curCont.invokeOnCompletion { /* TODO cancellation */ }
                    return@sc
                }
            }
        }
    }

    override fun offer(element: E): Boolean {
        while (true) {
            val head = _head
            if (head is Node.ReceiverNode) {
                if (headUpdater.compareAndSet(this, head, head.next)) {
                    val receiver = head.cont as CancellableContinuation<E>
                    val token = receiver.tryResume(element)
                    if (token != null) {
                        receiver.completeResume(token)
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
            if (head is Node.SenderNode) {
                if (headUpdater.compareAndSet(this, head, head.next)) {
                    val sender = head.cont
                    val token = sender.tryResume(Unit)
                    if (token != null) {
                        sender.completeResume(token)
                        return head.element as E
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
        val headUpdater = AtomicReferenceFieldUpdater.newUpdater(RendezvousChannelKovalStack::class.java, Node::class.java, "_head")
    }
}