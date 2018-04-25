package benchmarks

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.sync.AbstractSemaphore
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.Semaphore
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
open class MutexBenchmark {
    private val TOTAL_OPERATIONS = 1_000_000

    @Param("1", "2", "4", "8", "18", "36", "48", "72", "98", "144", "160", "200")
//    @Param("1", "4")
    private var threads: Int = 0

    @Param("1000", "10000")
    private var coroutines: Int = 0

    @Param("1", "2", "4", "10", "20")
    private var contentionDecreaseFactor: Int = 0

    @Param
    private lateinit var mutexViewCreator: MutexViewCreators

    private lateinit var mutexes: Array<MutexView>
    private lateinit var dispatcher: CoroutineDispatcher

    @Setup
    fun setup() {
        mutexes = Array(contentionDecreaseFactor, { mutexViewCreator.create() })
        dispatcher = ForkJoinPool(threads).asCoroutineDispatcher()
    }

    @Benchmark
    fun massiveRun() = runBlocking {
        val jobs = List(coroutines) {
            launch(dispatcher) {
                repeat(TOTAL_OPERATIONS / coroutines) {
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

internal interface MutexView {
    suspend fun lock()
    fun unlock()
}

internal enum class MutexViewCreators(val create: () -> MutexView) {
    MUTEX({object : MutexView {
        val m = Mutex()
        override suspend fun lock() = m.lock()
        override fun unlock() = m.unlock()
    }}),

    SEMAPHORE_MS_QUEUE_NONBLOCKING({object : MutexView {
        val m = kotlinx.coroutines.experimental.sync.impl.MSQueueNonblockingSemaphore(1)
        override suspend fun lock() = m.acquire()
        override fun unlock() = m.release()
    }}),

    SEMAPHORE_MS_QUEUE_BLOCKING({object : MutexView {
        val m = kotlinx.coroutines.experimental.sync.impl.MSQueueBlockingSemaphore(1)
        override suspend fun lock() = m.acquire()
        override fun unlock() = m.release()
    }}),

    SEMAPHORE_FFA_QUEUE_4_BLOCKING({object : MutexView {
        val m = kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(1, 4)
        override suspend fun lock() = m.acquire()
        override fun unlock() = m.release()
    }}),

    SEMAPHORE_FFA_QUEUE_8_BLOCKING({object : MutexView {
        val m = kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(1, 8)
        override suspend fun lock() = m.acquire()
        override fun unlock() = m.release()
    }}),

    SEMAPHORE_FFA_QUEUE_16_BLOCKING({object : MutexView {
        val m = kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(1, 16)
        override suspend fun lock() = m.acquire()
        override fun unlock() = m.release()
    }}),

    SEMAPHORE_FFA_QUEUE_32_BLOCKING({object : MutexView {
        val m = kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(1, 32)
        override suspend fun lock() = m.acquire()
        override fun unlock() = m.release()
    }}),

    SEMAPHORE_SYNCHRONIZED({object : MutexView {
        val m = kotlinx.coroutines.experimental.sync.impl.SynchronizedSemaphore(1)
        override suspend fun lock() = m.acquire()
        override fun unlock() = m.release()
    }});
}