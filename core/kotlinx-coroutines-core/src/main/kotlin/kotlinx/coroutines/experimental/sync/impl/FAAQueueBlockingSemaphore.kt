package kotlinx.coroutines.experimental.sync.impl

import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.sync.AbstractQueueWithSpinSemaphore
import sun.misc.Contended
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater


class FAAQueueBlockingSemaphore(maxPermits: Int, val segmentSize: Int = 16) : AbstractQueueWithSpinSemaphore(maxPermits) {
    private class Node(segmentSize: Int, item: Any?) {
        @Contended @Volatile @JvmField
        var deqidx = 0
        @Contended @Volatile @JvmField
        var enqidx = 1
        @Volatile @JvmField
        var next: Node? = null

        @JvmField val items = AtomicReferenceArray<Any?>(segmentSize)

        init {
            items[0] = item
        }
    }

    @Contended @Volatile
    private var head: Node
    @Contended @Volatile
    private var tail: Node

    init {
        val sentinelNode = Node(segmentSize,null)
        sentinelNode.enqidx = 0
        head = sentinelNode
        tail = sentinelNode
    }

    override fun offer(item: Any) {
        while (true) {
            val ltail = tail
            val idx = enqidxUpdater.getAndIncrement(ltail)
            if (idx > segmentSize - 1) { // This node is full
                if (ltail !== tail) continue
                val lnext = ltail.next
                if (lnext == null) {
                    val newNode = Node(segmentSize, item)
                    if (nextUpdater.compareAndSet(ltail, null, newNode)) {
                        tailUpdater.compareAndSet(this, ltail, newNode)
                        return
                    }
                } else {
                    tailUpdater.compareAndSet(this, ltail, lnext)
                }
                continue
            }
            if (ltail.items.compareAndSet(idx, null, item)) return
        }
    }

    override fun pollBlocking(): Any {
        while (true) {
            val lhead = head
            if (lhead.deqidx >= lhead.enqidx && lhead.next == null) continue
            val idx = deqidxUpdater.getAndIncrement(lhead)
            if (idx > segmentSize - 1) { // This node has been drained, check if there is another one
                if (lhead.next == null) continue
                headUpdater.compareAndSet(this, lhead, lhead.next)
                continue
            }
            val item = lhead.items.getAndSet(idx, TAKEN)
            if (item != null) return item
        }
    }

    private companion object {
        private val TAKEN = Symbol("TAKEN")

        @JvmField
        val tailUpdater = AtomicReferenceFieldUpdater.newUpdater(FAAQueueBlockingSemaphore::class.java, Node::class.java, "tail")
        @JvmField
        val headUpdater = AtomicReferenceFieldUpdater.newUpdater(FAAQueueBlockingSemaphore::class.java, Node::class.java, "head")
        @JvmField
        val nextUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Node::class.java, "next")

        @JvmField
        val deqidxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "deqidx")
        @JvmField
        val enqidxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "enqidx")
    }
}

