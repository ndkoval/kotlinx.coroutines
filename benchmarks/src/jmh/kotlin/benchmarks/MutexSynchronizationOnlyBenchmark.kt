package benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
open class MutexSynchronizationOnlyBenchmark {
    @Param("1", "2", "4", "10", "20", "50", "100")
    private var contentionDecreaseFactor: Int = 0

    @Param
    private lateinit var mutexViewCreator: MutexViewSpinWaitCreators

    private lateinit var mutexes: Array<MutexViewSpinWait>

    @Setup
    fun setup() {
        mutexes = Array(contentionDecreaseFactor, { mutexViewCreator.create() })
    }

    @Benchmark @Threads(1)
    fun bench_t1() = bench()

    @Benchmark @Threads(2)
    fun bench_t2() = bench()

    @Benchmark @Threads(4)
    fun bench_t4() = bench()

    @Benchmark @Threads(8)
    fun bench_t10() = bench()

    @Benchmark @Threads(18)
    fun bench_t18() = bench()

    @Benchmark @Threads(36)
    fun bench_t36() = bench()

    @Benchmark @Threads(48)
    fun bench_t48() = bench()

    @Benchmark @Threads(72)
    fun bench_t72() = bench()

    @Benchmark @Threads(98)
    fun bench_t98() = bench()

    @Benchmark @Threads(144)
    fun bench_t144() = bench()

    @Benchmark @Threads(160)
    fun bench_t160() = bench()

    @Benchmark @Threads(200)
    fun bench_t200() = bench()

    private fun bench() {
        val id = ThreadLocalRandom.current().nextInt(contentionDecreaseFactor)
        val mutex = mutexes[id]
        mutex.lock()
        try {
            Blackhole.consumeCPU(3)
        } finally {
            mutex.unlock()
        }
    }
}

internal interface MutexViewSpinWait {
    fun lock()
    fun unlock()
}

internal enum class MutexViewSpinWaitCreators(val create: () -> MutexViewSpinWait) {
    MUTEX({object : benchmarks.MutexViewSpinWait {
        val m = kotlinx.coroutines.experimental.sync.Mutex()
        override fun lock() { while (!m.tryLock()) {} }
        override fun unlock() = m.unlock()
    }}),

    SEMAPHORE_MS_QUEUE_NONBLOCKING({object : MutexViewSpinWait {
        val m = kotlinx.coroutines.experimental.sync.impl.MSQueueNonblockingSemaphore(1)
        override fun lock() = m.acquireSpinWait()
        override fun unlock() = m.releaseSpinWait()
    }}),

    SEMAPHORE_MS_QUEUE_BLOCKING({object : MutexViewSpinWait {
        val m = kotlinx.coroutines.experimental.sync.impl.MSQueueBlockingSemaphore(1)
        override fun lock() = m.acquireSpinWait()
        override fun unlock() = m.releaseSpinWait()
    }}),

    SEMAPHORE_FAA_QUEUE_4_BLOCKING({object : MutexViewSpinWait {
        val m = kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(1, 4)
        override fun lock() = m.acquireSpinWait()
        override fun unlock() = m.releaseSpinWait()
    }}),

    SEMAPHORE_FAA_QUEUE_8_BLOCKING({object : MutexViewSpinWait {
        val m = kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(1, 8)
        override fun lock() = m.acquireSpinWait()
        override fun unlock() = m.releaseSpinWait()
    }}),

    SEMAPHORE_FAA_QUEUE_16_BLOCKING({object : MutexViewSpinWait {
        val m = kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(1, 16)
        override fun lock() = m.acquireSpinWait()
        override fun unlock() = m.releaseSpinWait()
    }}),

    SEMAPHORE_FAA_QUEUE_32_BLOCKING({object : MutexViewSpinWait {
        val m = kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(1, 32)
        override fun lock() = m.acquireSpinWait()
        override fun unlock() = m.releaseSpinWait()
    }}),

    SEMAPHORE_SYNCHRONIZED({object : MutexViewSpinWait {
        val m = kotlinx.coroutines.experimental.sync.impl.SynchronizedSemaphore(1)
        override fun lock() = m.acquireSpinWait()
        override fun unlock() = m.releaseSpinWait()
    }})
}