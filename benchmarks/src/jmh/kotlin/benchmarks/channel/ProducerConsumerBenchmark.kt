package benchmarks.channel

import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import kotlin.math.max

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
open class ProducerConsumerBenchmark {
    private val SEND_OPERATIONS_APPROXIMATE = 500_000

    //    @Param("1", "2", "4", "8", "12", "16", "20", "30", "40", "60", "80") // dxLab server
    @Param("1", "2", "4", "8", "12", "18", "24", "36", "48", "72", "98", "144", "160", "200", "250") // IST server
    private var threads: Int = 0

    @Param("1", "10", "100", "1000")
    private var coroutinesMultiplier: Int = 0

    @Param("1", "2", "5", "20")
    private var contentionFactor: Int = 0

    @Param
    private lateinit var channelCreator: ChannelViewCreator

    private lateinit var channels: List<ChannelView>
    private lateinit var dispatcher: CoroutineDispatcher

    @Setup
    fun setup() {
        dispatcher = ForkJoinPool(threads).asCoroutineDispatcher()
        channels = List(contentionFactor, { channelCreator.create() })
    }

    fun mpmcBase(producers: Int, consumers: Int, blackhole: Blackhole) = runBlocking {
        val sendOperationsFactor = lcm(producers, consumers) * contentionFactor * coroutinesMultiplier
        val sendOperations = SEND_OPERATIONS_APPROXIMATE / sendOperationsFactor * sendOperationsFactor

        val jobs = List((producers + consumers) * coroutinesMultiplier) { index ->
            val isSender = (index % 2 == 0 && (index / 2) < (producers * coroutinesMultiplier))
                    || (index / 2) >= (consumers * coroutinesMultiplier)
            launch(dispatcher) {
                if (isSender) {
                    repeat(sendOperations / (producers * coroutinesMultiplier)) {
                        channels[it % contentionFactor].send(index)
                    }
                } else {
                    repeat(sendOperations / (consumers * coroutinesMultiplier)) {
                        blackhole.consume(channels[it % contentionFactor].receive())
                    }
                }
            }
        }
        jobs.forEach { it.join() }
    }

    @Benchmark
    fun mpmc(blackhole: Blackhole) = mpmcBase(max(1, threads / 2), max(1, threads / 2), blackhole)

    @Benchmark
    fun spmc(blackhole: Blackhole) = mpmcBase(1, max(1, threads - 1), blackhole)

    @Benchmark
    fun mpsc(blackhole: Blackhole) = mpmcBase(max(1, threads - 1), 1, blackhole)
}