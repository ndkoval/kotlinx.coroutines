package kotlinx.coroutines.experimental.sync.impl

import kotlinx.coroutines.experimental.sync.AbstractSemaphore
import kotlinx.coroutines.experimental.sync.SuspendStrategy
import java.util.*

class SynchronizedSemaphore(maxPermits: Int) : AbstractSemaphore(maxPermits) {
    private var _availablePermits = maxPermits
    private var _queue = ArrayDeque<Any>()

    override val availablePermits: Int get() = _availablePermits

    @Synchronized
    override fun tryAcquire(): Boolean {
        if (_availablePermits > 0) {
            _availablePermits--
            return true
        }
        return false
    }

    @Synchronized
    override fun acquireSuspend(item: Any, suspendStrategy: SuspendStrategy) {
        if (_availablePermits > 0) {
            _availablePermits--
            suspendStrategy.resume(item)
            return
        }
        suspendStrategy.suspendPrepare(item, {})
        _queue.offer(item)
    }

    @Synchronized
    override fun releaseImpl(suspendStrategy: SuspendStrategy) {
        while (true) {
            if (_availablePermits > 0 || _queue.isEmpty()) {
                check(availablePermits < maxPermits)
                _availablePermits++
                return
            } else {
                if (suspendStrategy.resumeIfNotCancelled(_queue.poll()))
                    return
            }
        }
    }
}