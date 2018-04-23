package kotlinx.coroutines.experimental.sync.impl

import kotlinx.coroutines.experimental.sync.AbstractQueueWithSpinSemaphore
import sun.misc.Contended
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

class MSQueueBlockingSemaphore(maxPermits: Int) : AbstractQueueWithSpinSemaphore(maxPermits) {
    private class Node(
            @Volatile @JvmField var _item: Any?,
            @Contended @Volatile @JvmField var _next: Node? = null
    )

    // Queue of continuations waiting for this semaphore
    @Contended @Volatile
    private var _head: Node
    @Contended @Volatile
    private var _tail: Node

    init {
        val dummyNode = Node(null)
        _head = dummyNode
        _tail = dummyNode
    }

    // MS queue add
    override fun offer(item: Any) {
        val newNode = Node(item)
        while (true) {
            val curTail = _tail
            val curTailNext = curTail._next
            if (curTailNext == null) {
                if (nextUpdater.compareAndSet(curTail, null, newNode)) {
                    tailUpdater.compareAndSet(this, curTail, newNode)
                    return
                }
            } else {
                tailUpdater.compareAndSet(this, curTail, curTailNext)
            }
        }
    }

    override fun pollBlocking(): Any {
        loop@ while (true) {
            val curHead = _head
            var curHeadNext = curHead._next
            // Spin wait until queue is not empty
            while (curHeadNext == null)
                curHeadNext = curHead._next

//            Optional code:
//            val curTail = _tail
//            if (curHead == curTail)
//                tailUpdater.compareAndSet(this, curTail, curHeadNext)

            // Remove an item
            if (maxPermits == 1) {
                 /// In case of one consumer only do not need in CAS
                _head = curHeadNext
            } else {
                if (!headUpdater.compareAndSet(this, curHead, curHeadNext))
                    continue@loop
            }
            val res =  curHeadNext._item!!
            itemUpdater.lazySet(curHeadNext, null)
            return res
        }
    }

    private companion object {
        @JvmStatic
        val nextUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Node::class.java, "_next")
        @JvmStatic
        val itemUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Any::class.java, "_item")
        @JvmStatic
        val headUpdater = AtomicReferenceFieldUpdater.newUpdater(MSQueueBlockingSemaphore::class.java, Node::class.java, "_head")
        @JvmStatic
        val tailUpdater = AtomicReferenceFieldUpdater.newUpdater(MSQueueBlockingSemaphore::class.java, Node::class.java, "_tail")
    }
}