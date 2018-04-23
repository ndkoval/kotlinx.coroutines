package kotlinx.coroutines.experimental.sync.impl

import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.sync.AbstractSemaphore
import kotlinx.coroutines.experimental.sync.SuspendStrategy
import sun.misc.Contended
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

class MSQueueNonblockingSemaphore(maxPermits: Int) : AbstractSemaphore (maxPermits){
    private class Node(
            @JvmField @Volatile var _cont: Any?,
            @JvmField @Volatile var _nextOrState: Any = 0
    )

    @Contended @Volatile
    private var _head: Node
    @Contended @Volatile
    private var _tail: Node

    init {
        val initialNode = Node(_cont = null, _nextOrState = maxPermits)
        _head = initialNode
        _tail = initialNode
    }

    override val availablePermits: Int get() = _tail._nextOrState as? Int ?: 0

    override fun tryAcquire(): Boolean {
        val tail = _tail
        while (true) {
            val nextOrState = tail._nextOrState
            if (nextOrState !is Int || nextOrState == 0) return false
            val newState = nextOrState - 1
            if (nextOrStateUpdater.compareAndSet(tail, nextOrState, newState)) return true
        }
    }

    override fun acquireSuspend(item: Any, suspendStrategy: SuspendStrategy) {
        var node: Node? = null
        while (true) {
            val tail = _tail
            val nextOrState = tail._nextOrState
            when (nextOrState) {
                0 -> {
                    if (node == null) node = Node(item)
                    if (nextOrStateUpdater.compareAndSet(tail, nextOrState, node)) {
                        tailUpdater.compareAndSet(this, tail, node)
                        suspendStrategy.suspendPrepare(item, { /* TODO on cancel */ })
                        return
                    }
                }
                is Int -> {
                    val newState = nextOrState - 1
                    if (nextOrStateUpdater.compareAndSet(tail, nextOrState, newState)) {
                        suspendStrategy.resume(item)
                        return
                    }
                }
                is Node -> {
                    tailUpdater.compareAndSet(this, tail, nextOrState)
                }
                else -> error("Invalid _tail._nextOrState: $nextOrState")
            }
        }
    }

    override fun releaseImpl(suspendStrategy: SuspendStrategy) {
        while (true) {
            val head = _head
            val nextOrState = head._nextOrState
            when (nextOrState) {
                is Int -> {
                    val newState = nextOrState + 1
                    check(newState <= maxPermits, { "Release operation cannot be invoked before acquire" })
                    if (nextOrStateUpdater.compareAndSet(head, nextOrState, newState)) return
                }
                is Node -> {
                    if (headUpdater.compareAndSet(this, head, nextOrState)) {
                        val cont = nextOrState._cont!!
                        contUpdater.lazySet(nextOrState, null)
                        if (suspendStrategy.resumeIfNotCancelled(cont))
                            return
                    }
                }
                else -> error("Invalid _head._nextOrState: $nextOrState")
            }
        }
    }

    private companion object {
        @JvmStatic
        private val headUpdater = AtomicReferenceFieldUpdater.newUpdater(MSQueueNonblockingSemaphore::class.java, Node::class.java, "_head")
        @JvmStatic
        private val tailUpdater = AtomicReferenceFieldUpdater.newUpdater(MSQueueNonblockingSemaphore::class.java, Node::class.java, "_tail")

        @JvmStatic
        private val contUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Any::class.java, "_cont")
        @JvmStatic
        private val nextOrStateUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Any::class.java, "_nextOrState")
    }
}