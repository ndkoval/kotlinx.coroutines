package benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
open class MutexSynchronizationOnlyBenchmark {
    private val TOTAL_OPERATIONS = 1_000_000

    @Param("1", "2", "4", "8", "18", "36", "48", "72", "98", "144")
//    @Param("1", "4")
    private var threads: Int = 0

    @Param("1", "2", "4", "10", "20")
    private var contentionDecreaseFactor: Int = 0

    @Param
    private lateinit var mutexViewCreator: MutexViewSpinWaitCreators

    private lateinit var mutexes: Array<MutexViewSpinWait>

    @Setup
    fun setup() {
        mutexes = Array(contentionDecreaseFactor, { mutexViewCreator.create() })
    }

    @Benchmark
    fun massiveRun() {
        val jobs = List(threads) {
            thread {
                repeat(TOTAL_OPERATIONS / threads) {
                    val id = ThreadLocalRandom.current().nextInt(contentionDecreaseFactor)
                    val mutex = mutexes[id]
                    mutex.lock()
                    try {
                        Blackhole.consumeCPU(5)
                    } finally {
                        mutex.unlock()
                    }
                }
            }
        }
        jobs.forEach { it.join() }
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