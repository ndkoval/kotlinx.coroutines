package benchmarks

import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.channels.koval.ChannelKoval
import kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKoval
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.sync.Mutex
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
open class ChannelBenchmark {
    private val TOTAL_OPERATIONS = 1_000_000

//    @Param("1", "2", "4", "8", "18", "36", "48", "72", "98", "144", "160", "200")
    @Param("1", "4", "18", "36")
    private var threads: Int = 0

    @Param("2", "10", "100", "1000", "10000")
    private var coroutines: Int = 0

    private val rendezvousChK = RendezvousChannelKoval<Int>()
    private val rendezvousChE = RendezvousChannel<Int>()

    private lateinit var dispatcher: CoroutineDispatcher

    @Setup
    fun setup() {
        dispatcher = ForkJoinPool(threads).asCoroutineDispatcher()
    }

    @Benchmark
    fun producerConsumerBalancedKoval(blackhole: Blackhole) = runBlocking {
        val jobs = List(coroutines) { index ->
            launch(dispatcher) {
                repeat(TOTAL_OPERATIONS / coroutines) {
                    if (index % 2 == 0) {
                        rendezvousChK.send(index)
                    } else {
                        blackhole.consume(rendezvousChK.receive())
                    }
                }
            }
        }
        jobs.forEach { it.join() }
    }

    @Benchmark
    fun producerConsumerBalancedElizarov(blackhole: Blackhole) = runBlocking {
        val jobs = List(coroutines) { index ->
            launch(dispatcher) {
                repeat(TOTAL_OPERATIONS / coroutines) {
                    if (index % 2 == 0) {
                        rendezvousChE.send(index)
                    } else {
                        blackhole.consume(rendezvousChE.receive())
                    }
                }
            }
        }
        jobs.forEach { it.join() }
    }
}