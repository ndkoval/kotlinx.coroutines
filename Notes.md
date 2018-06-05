# Problems to solve

1. Nonblocking rendezvous channel implementation **(done)**
   + Should be lock-free **(have idea)**
2. Closing and cancellation support **(have idea)**
3. Extending to buffered (both limited and unlimited) channel
4. Select expression
5. Broadcast/conflated
   + This is more about implememtation, out of the research scope

# Publications plan

1. Lock-free rendezvous channel (+ select, closing, cancellation)
2. Zoo of synchronization primitives for CSP
   (buffered channels, broadcast channels, conflated channels, semaphore, mutex, pool)
3. Scalable relaxed channels (no ordering, almost fifo, local linearizable, ...)
4. Efficient embedded cancellation for continuations in programming via sequential processes

# Rendezvous channel
Channel is a simple abstraction to transfer a stream of values between coroutines. Two basic operations form a channel: `send(val: T)` and `receive(): T`. The standard channel is conceptually very similar to `BlockingQueue` with one element. One key difference is that instead of a blocking put operation it has a suspending send, and instead of a blocking take operation it has a suspending receive. 

Here is a basic channel interface:

```java
interface Channel<E> {
    suspend fun send(element: E)
    fun offer(element: E): Boolean

    suspend fun receive(): E
    fun poll(): E?

    // Cannot invoke `send` on a closed channel, 
    // `receive` can retrieve already waiting senders
    fun close(cause: Throwable? = null): Boolean
}
```

When a coroutine becomes blocked it is added to the waiting queue for this channel. Note that only one type of channel utilizers (sender or receiver) could be in the queue, and this condition should be checked during a `push` operation. Here is an example of such queue contract.

```java
class WaitingQueue {
  
  /*
   * Atomically checks that the queue does not 
   * have any receiver and adds sender to it, 
   * otherwise polls the first receiver
   */
  fun addSenderOrPollReceiver(Coroutine c): Coroutine? { 
    ... 
  }
  
  /**
   * Atomically checks that the queue does not 
   * have any sender and adds receiver to it,
   * otherwise polls the first sender
   */
  fun addReceiverOrPollSender(Coroutine c): Coroutine? {
    ...
  }
}
```

The current implementation is based on concurrent doubly-linked list with DCSS descriptors for every `offer` operation in order to check is it valid to add this type of coroutine to the queue or not. First of all, doubly-linked list is used for fast cancellation, which could be organized using flat-combining technique and work in O(1) in average. It also seems that it is possible not to use descriptors for send/receive operations. 

The main idea of such implementation is based on MS queue, but with segment in order to store several values in a node, so fewer allocations (and GC work) are required and a node could be cached for several send/receive operations. For this purpose, each node has a `_data` array (usually with size 16 or 32), in which coroutines with the elements to be sent are stored. In order to determine is this coroutine receiver or sender, a special `RECEIVER_ELEMENT` is stored for receivers. In order to manage this array, special `_deqIdx` and `_enqIdx` are used which specify indexes for next deque and enque operations respectively. Alike standard MS queue nodes, this implementation has `_next` field to the next node and MS queue algorithm is used for adding/removing these segment nodes.

Code for `Node` class:

```java
private class Node {
  var _deqIdx = 0
  var _enqIdx = 0
  _data = AtomicReferenceArray<Any?>(SEGMENT_SIZE * 2)
  
  _next: Node? = null
}
```

The main problem is to guarantee lock-freedom with such structure. In this document blocking version (with spin waits) is presented at first, then the algorithm is extended to obstruction-free and to lock-free.

The blocking algorithm is pretty simple and is presented in the code block below.

```java
// element is `RECEIVER_ELEMENT` for `receive()` method
private suspend fun sendOrReceiveSuspend(element: Any, curCont: Continuation) {
    try_again@ while (true) { // CAS loop
        // Read the tail and its enqueue index at first, then the head and its indexes.
        var tail = _tail
        var tailEnqIdx = tail._enqIdx
        var head = _head
        var headDeqIdx = head._deqIdx
        var headEnqIdx = head._enqIdx
        // If `headDeqIdx == headEnqIdx`, either the waiting queue is empty or the `head` node is full
        if (headDeqIdx == headEnqIdx) {
            if (headDeqIdx == segmentSize) {
                // The `head` node is full, ry to move head pointer forward
                if (adjustHead(head)) continue@try_again
                // Queue is empty, try to add a new node with the current coroutine
                if (addNewNode(head, curCont, element)) return suspend(curCont)
            } else {
                // The `head` node is not full but empty, 
                // try to store the current continuation
                if (storeContinuation(head, headEnqIdx, curCont, element)) return suspend(curCont)
            }
        } else {
            var firstElement = readElementSpin(head, headDeqIdx) // spin until element is non-null
            val makeRendezvous = if (element == RECEIVER_ELEMENT) firstElement != RECEIVER_ELEMENT else firstElement == RECEIVER_ELEMENT
            if (makeRendezvous) {
                if (tryResumeContinuation(head, headDeqIdx, element)) {
                    return (if (element == RECEIVER_ELEMENT) firstElement else Unit) as T
                }
            } else {
                // Try to add a new node with the current continuation and element
                // if the tail is full, otherwise try to store it at the `tailEnqIdx` index.
                if (tailEnqIdx == segmentSize) {
                    if (addNewNode(tail, curCont, element)) suspend(curCont)
                } else {
                    if (storeContinuation(tail, tailEnqIdx, curCont, element)) return suspend(curCont)
                }
            }
        }
    }
}
```


