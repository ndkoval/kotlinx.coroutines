package benchmarks

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.sync.Semaphore
import kotlinx.coroutines.experimental.sync.withSemaphore
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
open class SemaphoreBenchmark {
    @Param("1", "2", "4", "8", "18", "36", "48", "72", "98", "144", "160", "200")
    private var n: Int = 0

    @Param("1", "2", "4", "10", "50", "100")
    private var maxPermits: Int = 0

    @Param
    private lateinit var semaphoreCreator: SemaphoreCreators


    private lateinit var semaphore: Semaphore

    @Setup
    fun setup() {
        semaphore = semaphoreCreator.create(maxPermits)
    }

    @Benchmark
    fun massiveRun() = runBlocking {
        val jobs = List(n) {
            launch(CommonPool) {
                repeat(K / n) {
                    semaphore.withSemaphore { Blackhole.consumeCPU(10) }
                }
            }
        }
        jobs.forEach { it.join() }
    }
}

private const val K = 500_000

internal enum class SemaphoreCreators(val create: (maxPermits: Int) -> Semaphore) {
    SEMAPHORE_MS_QUEUE_NONBLOCKING({ maxPermits -> kotlinx.coroutines.experimental.sync.impl.MSQueueNonblockingSemaphore(maxPermits) }),
    SEMAPHORE_MS_QUEUE_BLOCKING({ maxPermits -> kotlinx.coroutines.experimental.sync.impl.MSQueueBlockingSemaphore(maxPermits) }),
    SEMAPHORE_FAA_QUEUE_4_BLOCKING({ maxPermits -> kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(maxPermits, 4) }),
    SEMAPHORE_FAA_QUEUE_8_BLOCKING({ maxPermits -> kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(maxPermits, 8) }),
    SEMAPHORE_FAA_QUEUE_16_BLOCKING({ maxPermits -> kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(maxPermits, 16) }),
    SEMAPHORE_FAA_QUEUE_32_BLOCKING({ maxPermits -> kotlinx.coroutines.experimental.sync.impl.FAAQueueBlockingSemaphore(maxPermits, 32) })
}