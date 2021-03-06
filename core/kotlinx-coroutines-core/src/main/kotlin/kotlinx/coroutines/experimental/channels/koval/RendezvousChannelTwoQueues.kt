package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.channels.koval.FAAQueue.Companion.TAKEN_CONTINUATION
import kotlinx.coroutines.experimental.suspendAtomicCancellableCoroutine

class RendezvousChannelTwoQueues<E>(segmentSize: Int = 32) : ChannelKoval<E> {
    private val receivers = FAAQueue<CancellableContinuation<*>>(segmentSize)
    private val senders = FAAQueue<CancellableContinuation<*>>(segmentSize)

    override suspend fun send(element: E) {
        // Try to send without suspending at first,
        // invoke suspend implementation if it is not succeed.
        if (offer(element)) return
        sendSuspend(element)
    }

    override suspend fun receive(): E {
        // Try to send without suspending at first,
        // invoke suspend implementation if it is not succeed.
        return poll() ?: receiveSuspend()
    }

    suspend fun sendSuspend(element: E) = suspendAtomicCancellableCoroutine<Unit>(holdCancellability = true) sc@ { curCont ->
        curCont.data = element
        while (true) {
            prepareStatus(curCont)
            var enqNode: FAAQueue.Node? = null
            var enqIdx: Int = -1
            enqueue@while (true) {
                val tail = senders._tail
                val idx = FAAQueue.enqIdxUpdater.getAndIncrement(tail)
                if (idx > senders.segmentSize - 1) { // This node is full
                    if (tail !== senders._tail) continue
                    val next = tail._next
                    if (next == null) {
                        val node = FAAQueue.Node(senders.segmentSize, curCont)
                        if (FAAQueue.nextUpdater.compareAndSet(tail, null, node)) {
                            FAAQueue.tailUpdater.compareAndSet(senders, tail, node)
                            enqNode = node; enqIdx = 0
                            break@enqueue
                        }
                    } else {
                        FAAQueue.tailUpdater.compareAndSet(senders, tail, next)
                    }
                    continue
                }
                if (tail.casContinuation(idx, null, curCont)) {
                    enqNode = tail; enqIdx = idx
                    break@enqueue
                }
            }
            if (!checkCont(curCont) { receivers.isEmpty() }) {
                enqNode!!.setContinuation(enqIdx, TAKEN_CONTINUATION)
                if (offer(element)) {
                    curCont.resume(Unit)
                    return@sc
                }
            } else {
                curCont.initCancellability()
                return@sc
            }
        }
    }

    suspend fun receiveSuspend(): E = suspendAtomicCancellableCoroutine(holdCancellability = true) sc@ { curCont ->
        while (true) {
            prepareStatus(curCont)
            var enqNode: FAAQueue.Node? = null
            var enqIdx: Int = -1
            enqueue@while (true) {
                val tail = receivers._tail
                val idx = FAAQueue.enqIdxUpdater.getAndIncrement(tail)
                if (idx > receivers.segmentSize - 1) { // This node is full
                    if (tail !== receivers._tail) continue
                    val next = tail._next
                    if (next == null) {
                        val node = FAAQueue.Node(receivers.segmentSize, curCont)
                        if (FAAQueue.nextUpdater.compareAndSet(tail, null, node)) {
                            FAAQueue.tailUpdater.compareAndSet(receivers, tail, node)
                            enqNode = node; enqIdx = 0
                            break@enqueue
                        }
                    } else {
                        FAAQueue.tailUpdater.compareAndSet(receivers, tail, next)
                    }
                    continue
                }
                if (tail.casContinuation(idx, null, curCont)) {
                    enqNode = tail; enqIdx = idx
                    break@enqueue
                }
            }
            if (!checkCont(curCont) { senders.isEmpty() }) {
                enqNode!!.setContinuation(enqIdx, TAKEN_CONTINUATION)
                val res = poll()
                if (res != null) {
                    curCont.resume(res)
                    return@sc
                }
            } else {
                curCont.initCancellability()
                return@sc
            }
        }
    }

    fun prepareStatus(cont: CancellableContinuation<*>) {
        val curStatus = cont.status.get() // 0 -- undef, 1 -- good, 2 -- bad
        val epoch = curStatus / 4
        cont.status.set((epoch + 1) * 4)
    }

    inline fun checkCont(cont: CancellableContinuation<*>, crossinline block: () -> Boolean): Boolean {
        val curStatus = cont.status.get() // 0 -- undef, 1 -- good, 2 -- bad
        val epoch = curStatus / 4
        val status = curStatus % 4
        if (status == 0L) {
            val value = if (block()) 1L else 2L
            if (cont.status.compareAndSet(curStatus, epoch * 4 + value)) {
                return if (value == 1L) true else false
            } else {
                val updStatus = cont.status.get()
                val updEpoch = updStatus  / 4
                if (updEpoch != epoch) return false
                return if (updStatus % 4 == 1L) true else false
            }
        } else {
            return if (status == 1L) true else false
        }
    }

    override fun offer(element: E): Boolean {
        while (true) {
            val r = receivers.dequeue() ?: return false
            if (!checkCont(r) { senders.isEmpty() } ) continue
            r as CancellableContinuation<E>
            val token = r.tryResume(element)
            if (token != null) {
                r.completeResume(token)
                return true
            }
        }
    }

    override fun poll(): E? {
        while (true) {
            val s = senders.dequeue() ?: return null
            if (!checkCont(s) { receivers.isEmpty() } ) continue
            s as CancellableContinuation<Unit>
            val token = s.tryResume(Unit)
            if (token != null) {
                val res = s.data
                s.data = null
                s.completeResume(token)
                return res as E?
            }
        }
    }

    override fun close(cause: Throwable?): Boolean {
        TODO("not implemented")
    }

    override val onSend: SelectClause2<E, ChannelKoval<E>>
        get() = TODO("not implemented")

    override val onReceive: SelectClause1<E>
        get() = TODO("not implemented")
}