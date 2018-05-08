package kotlinx.coroutines.experimental.channels.koval

import com.sun.org.apache.xpath.internal.operations.Bool
import kotlinx.coroutines.experimental.internal.Symbol
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater




internal class FAAQueue<E>(val segmentSize: Int = 128, val spinThreshold: Int = 300) {
    internal class Node(segmentSize: Int) {
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

        constructor(segmentSize: Int, cont: Any) : this(segmentSize) {
            _enqIdx = 1
            _data[0] = cont
        }

        inline fun casContinuation(index: Int, expect: Any?, update: Any): Boolean {
            return UNSAFE.compareAndSwapObject(_data, byteOffset(index), expect, update)
        }

        inline fun getAndSetContinuation(index: Int, newCont: Any?): Any? {
            return UNSAFE.getAndSetObject(_data, byteOffset(index), newCont)
        }

        inline fun getContinuation(index: Int): Any? {
            return UNSAFE.getObjectVolatile(_data, byteOffset(index))
        }

        inline fun setContinuation(index: Int, cont: Any?): Any? {
            return UNSAFE.putObjectVolatile(_data, byteOffset(index), cont)
        }
    }

    @Volatile internal var _head: Node
    @Volatile internal var _tail: Node

    init {
        // Initialize queue with empty node similar to MS queue
        // algorithm, but this node is just empty, not sentinel.
        val emptyNode = Node(segmentSize)
        _head = emptyNode
        _tail = emptyNode
    }

    fun enqueue(item: E) {
        while (true) {
            val tail = _tail
            val idx = enqIdxUpdater.getAndIncrement(tail)
            if (idx > segmentSize - 1) { // This node is full
                if (tail !== _tail) continue
                val next = tail._next
                if (next == null) {
                    val node = Node(segmentSize, item!!)
                    if (nextUpdater.compareAndSet(tail, null, node)) {
                        tailUpdater.compareAndSet(this, tail, node)
                        return
                    }
                } else {
                    tailUpdater.compareAndSet(this, tail, next)
                }
                continue
            }
            if (tail.casContinuation(idx, null, item!!)) return
        }
    }

    fun dequeue(): E? {
        while (true) {
            val head = _head
            if (head._deqIdx >= head._enqIdx && head._next == null) return null
            val idx = deqIdxUpdater.getAndIncrement(head)
            if (idx > segmentSize - 1) { // This node has been drained, check if there is another one
                if (head._next == null) return null;  // No more nodes in the queue
                headUpdater.compareAndSet(this, head, head._next)
                continue
            }
            var item = head.getContinuation(idx)
            var attempt = 0
            do {
                if (item != null) break
                item = head.getContinuation(idx)
                attempt++
            } while (attempt < spinThreshold)
            if (item == TAKEN_CONTINUATION) continue
            if (item == null) {
                if (head.casContinuation(idx, null, TAKEN_CONTINUATION)) continue
                item = head.getContinuation(idx)
                if (item == TAKEN_CONTINUATION) continue
            }
            return item as E
        }
    }

    fun isEmpty(): Boolean {
        val head = _head
        return head._deqIdx >= head._enqIdx && head._next == null
    }

    internal companion object {
        @JvmField val UNSAFE = UtilUnsafe.unsafe
        @JvmField val base = UNSAFE.arrayBaseOffset(Array<Any>::class.java)
        @JvmField val shift = 31 - Integer.numberOfLeadingZeros(UNSAFE.arrayIndexScale(Array<Any>::class.java))
        @JvmStatic inline fun byteOffset(i: Int) = (i.toLong() shl shift) + base

        @JvmField val TAKEN_CONTINUATION = Symbol("TAKEN_CONTINUATION")

        @JvmField val tailUpdater = AtomicReferenceFieldUpdater.newUpdater(FAAQueue::class.java, Node::class.java, "_tail")
        @JvmField val headUpdater = AtomicReferenceFieldUpdater.newUpdater(FAAQueue::class.java, Node::class.java, "_head")
        @JvmField val nextUpdater = AtomicReferenceFieldUpdater.newUpdater(Node::class.java, Node::class.java, "_next")

        @JvmField val deqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_deqIdx")
        @JvmField val enqIdxUpdater = AtomicIntegerFieldUpdater.newUpdater(Node::class.java, "_enqIdx")
    }
}