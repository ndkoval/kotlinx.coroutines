package kotlinx.coroutines.experimental.sync

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2
import kotlinx.coroutines.experimental.selects.SelectInstance
import kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore
import kotlinx.coroutines.experimental.sync.impl.MSQueueBlockingSemaphore
import kotlinx.coroutines.experimental.sync.impl.MSQueueNonblockingSemaphore
import kotlinx.coroutines.experimental.sync.impl.SynchronizedSemaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * This is a counting semaphore for coroutines. Conceptually,
 * it maintains a set of [available permits][availablePermits].
 * Each [acquire] gets a permit or suspends until a permit is available.
 * On the other hand, [release] returns a permit to the semaphore and
 * resumes a waiting coroutine if needed.
 */
interface Semaphore {
    /**
     * The maximum number of permits available in this semaphore.
     */
    val maxPermits: Int

    /**
     * The current number of permits available in this semaphore.
     */
    val availablePermits: Int

    /**
     * Returns `true` if there are available permits in this semaphore
     * and [acquire] attempt will not suspend, `false` otherwise.
     *
     * Note that it is not guaranteed that [acquire] attempt will not suspend
     * after this method returns `true` if other coroutines can invoke
     * [acquire] on this semaphore.
     */
    val hasPermits: Boolean get() = availablePermits != 0


    /**
     * Acquires a permit from this semaphore only if the one is
     * available at the time of invocation. Returns `true` if
     * a permit was acquired, `false` otherwise.
     */
    fun tryAcquire(): Boolean

    /**
     * Acquires a permit from this semaphore, suspending the caller
     * while this semaphore does not [has permits][hasPermits].
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     *
     * *Cancellation of suspended send is atomic* -- when this function
     * throws [CancellationException] it means that the [element] was not sent to this channel.
     * As a side-effect of atomic cancellation, a thread-bound coroutine (to some UI thread, for example) may
     * continue to execute even after it was cancelled from the same thread in the case when this send operation
     * was already resumed and the continuation was posted for execution to the thread's queue.
     *
     * Note, that this function does not check for cancellation when it is not suspended.
     * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
     */
    suspend fun acquire()

    /**
     * Releases a permit and returning it to this semaphore.
     * If any threads are waiting for a permit, then one is selected
     * and resumed with giving the permit.
     *
     * Throws [IllegalStateException] if this [release] is invoked
     * without a conjugate acquire.
     */
    fun release()

    /**
     * Clause for [select] expression of [acquire] suspending function that selects when this semaphore is acquired.
     * When the clause is selected the reference to this semaphore is passed into the corresponding block.
     */
    val onSemaphore: SelectClause1<Semaphore>
}

/**
 * Creates a [Semaphore] instance.
 */
fun Semaphore(maxPermits: Int): Semaphore {
    require(maxPermits >= 0, { "maxPer mits should be greater than zero" })
    return MSQueueNonblockingSemaphore(maxPermits)
}

/**
 * Executes the given [acton] using this semaphore permit.
 *
 * @return the return value of the action.
 */
suspend inline fun <T> Semaphore.withSemaphore(acton: () -> T): T {
    acquire()
    try {
        return acton()
    } finally {
        release()
    }
}







abstract class AbstractSemaphore(override val maxPermits: Int) : Semaphore, SelectClause1<Semaphore> {
    override suspend fun acquire() {
        if (tryAcquire()) return // fast path
        suspendAtomicCancellableCoroutine<Unit>(holdCancellability = true) { cont ->
            acquireSuspend(cont, SuspendStrategy.SUSPEND)
        }
    }

    open fun acquireSpinWait() {
        if (tryAcquire()) return // fast path
        val state = AtomicInteger()
        acquireSuspend(state, SuspendStrategy.SPIN)
        while (state.get() != SPIN_RESUMED) { /* Thread.onSpinWait() */ }
    }

    internal abstract fun acquireSuspend(item: Any, suspendStrategy: SuspendStrategy)

    override fun release() = releaseImpl(SuspendStrategy.SUSPEND)

    fun releaseSpinWait() = releaseImpl(SuspendStrategy.SPIN)

    internal abstract fun releaseImpl(suspendStrategy: SuspendStrategy)

    override val onSemaphore: SelectClause1<Semaphore> get() = this

    override fun <R> registerSelectClause1(select: SelectInstance<R>, block: suspend (Semaphore) -> R) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

internal const val SPIN_INITIAL = 0
internal const val SPIN_SUSPENDED = 1
internal const val SPIN_RESUMED= 2

internal enum class SuspendStrategy {
    SUSPEND {
        override fun suspendPrepare(item: Any, onCancel: () -> Unit) {
            item as CancellableContinuation<Unit>
            item.initCancellability()
            item.invokeOnCompletion { onCancel() }
        }

        override fun resume(item: Any) {
            item as CancellableContinuation<Unit>
            item.resume(Unit)
        }

        override fun resumeIfNotCancelled(item: Any): Boolean {
            item as CancellableContinuation<Unit>
            val token = item.tryResume(Unit)
            if (token != null) { // not cancelled
                item.completeResume(token)
                return true
            }
            return false
        }
    },
    SPIN {
        override fun suspendPrepare(item: Any, onCancel: () -> Unit) {
            item as AtomicInteger
            item.compareAndSet(SPIN_INITIAL, SPIN_SUSPENDED)
        }

        override fun resume(item: Any) {
            item as AtomicInteger
            item.set(SPIN_RESUMED)
        }

        override fun resumeIfNotCancelled(item: Any): Boolean {
            resume(item)
            return true
        }
    };

    abstract fun suspendPrepare(item: Any, onCancel: () -> Unit)
    abstract fun resume(item: Any)
    abstract fun resumeIfNotCancelled(item: Any): Boolean
}


abstract class AbstractQueueWithSpinSemaphore(maxPermits: Int) : AbstractSemaphore(maxPermits) {
    // Number of available permits if >= 0, number of continuations in the queue with minus sign if < 0.
    // Note that #acquire() operation decrements the state at first and only then adds the continuation to the queue,
    // so #release() could spin on the queue in order to wait for the continuation to be resumed.
    private val _state: AtomicInt = atomic(maxPermits)

    override val availablePermits: Int get() {
        val state = _state.value
        return if (state >= 0) state else 0
    }

    override fun tryAcquire(): Boolean {
        // Cannot use FAA here because we need to be sure
        // that after the decrement state is non-negative.
        // Use CAS loop instead.
        val state = _state.value
        if (state > 0 && _state.compareAndSet(state, state - 1))
            return true // Enough permits were available, acquired
        return false
    }

    override suspend fun acquire() {
        val oldState = _state.getAndDecrement()
        if (oldState > 0) return // Fast path: enough permits were available, acquired
        // Slow path: need to offer the current continuation to the queue and suspend
        suspendAtomicCancellableCoroutine<Unit>(holdCancellability = true) {
            cont -> acquireSuspend(cont, SuspendStrategy.SUSPEND)
        }
    }

    override fun acquireSpinWait() {
        val oldState = _state.getAndDecrement()
        if (oldState > 0) return // Fast path: enough permits were available, acquired
        // Slow path: need to offer the current continuation to the queue and suspend
        val state = AtomicInteger()
        acquireSuspend(state, SuspendStrategy.SPIN)
        while (state.get() != SPIN_RESUMED) { /* Thread.onSpinWait() */ }
    }

    override fun acquireSuspend(item: Any, suspendStrategy: SuspendStrategy) {
        suspendStrategy.suspendPrepare(item, {})
        offer(item)
    }

    override fun releaseImpl(suspendStrategy: SuspendStrategy) {
        while (true) {
            val oldState = _state.getAndIncrement()
            check(oldState < maxPermits, { "Release operation cannot be invoked before acquire" })
            if (oldState >= 0) return // No continuations need to be resumed
            // Need to resume a continuation, spin until it is added to the queue
            val item = pollBlocking()
            // Try to resume the continuation and return
            if (suspendStrategy.resumeIfNotCancelled(item))
                return
            // Continuation has been cancelled, try to resume the next continuation if needed
        }
    }

    protected abstract fun offer(item: Any)
    protected abstract fun pollBlocking(): Any
}