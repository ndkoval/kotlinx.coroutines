package benchmarks.channel

import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKoval
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
open class SelectBenchmark {
    private val SEND_OPERATIONS_APPROXIMATE = 500_000

    // Param("1", "2", "4", "8", "12", "16", "20", "30", "40", "60", "80") // dxLab server
    @Param("1", "2", "4", "8", "12", "18", "24", "36", "48", "60", "72", "98", "144", "160", "200", "250") // IST server
    private var threads: Int = 0

    @Param("1", "10", "100", "1000")
    private var coroutinesMultiplier: Int = 0

    @Param("1", "2", "5", "20")
    private var channelsToSelect: Int = 0

    private lateinit var channelsListSegments: List<RendezvousChannelKoval<Int>>
    private lateinit var channelsListCurrent: List<RendezvousChannel<Int>>
    private lateinit var dispatcher: CoroutineDispatcher

    @Setup
    fun setup() {
        dispatcher = ForkJoinPool(threads).asCoroutineDispatcher()
        channelsListCurrent = List(channelsToSelect, { RendezvousChannel<Int>() })
        channelsListSegments = List(channelsToSelect, { RendezvousChannelKoval<Int>() })
    }

    fun mpmcCurrentBase(producers: Int, consumers: Int, blackhole: Blackhole) = runBlocking {
        val sendOperationsFactor = lcm(producers, consumers) * coroutinesMultiplier // 23 1 10
        val sendOperations = SEND_OPERATIONS_APPROXIMATE / sendOperationsFactor * sendOperationsFactor

        val jobs = List((producers + consumers) * coroutinesMultiplier) { index ->
            val sender = (index % 2 == 0 && (index / 2 + 1) <= (producers * coroutinesMultiplier))
                    || (index / 2 + 1) > (consumers * coroutinesMultiplier)
            launch(dispatcher) {
                if (sender) {
                    repeat(sendOperations / (producers * coroutinesMultiplier)) {
                        kotlinx.coroutines.experimental.selects.select {
                            channelsListCurrent.forEach {
                                ch -> ch.onSend(index) { /* Do nothing */}
                            }
                        }
                    }
                } else {
                    repeat(sendOperations / (consumers * coroutinesMultiplier)) {
                        kotlinx.coroutines.experimental.selects.select<Unit> {
                            channelsListCurrent.forEach {
                                ch -> ch.onReceive { blackhole.consume(it) }
                            }
                        }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
    }

    @Benchmark
    fun mpmcCurrent(blackhole: Blackhole) = mpmcCurrentBase(max(1, threads / 2), max(1, threads / 2), blackhole)

//    @Benchmark
//    fun spmcCurrent(blackhole: Blackhole) = mpmcCurrentBase(1, max(1, threads - 1), blackhole)

//    @Benchmark
//    fun mpscCurrent(blackhole: Blackhole) = mpmcCurrentBase(max(1, threads - 1), 1, blackhole)


    /* TODO: uncomment when select is implemented for segment-based channel

    fun mpmcSegmentsBase(producers: Int, consumers: Int, blackhole: Blackhole) = runBlocking {
        val sendOperationsFactor = lcm(producers, consumers) * coroutinesMultiplier // 23 1 10
        val sendOperations = SEND_OPERATIONS_APPROXIMATE / sendOperationsFactor * sendOperationsFactor

        val jobs = List((producers + consumers) * coroutinesMultiplier) { index ->
            val sender = (index % 2 == 0 && (index / 2 + 1) <= (producers * coroutinesMultiplier))
                    || (index / 2 + 1) > (consumers * coroutinesMultiplier)
            launch(dispatcher) {
                if (sender) {
                    repeat(sendOperations / (producers * coroutinesMultiplier)) {
                        kotlinx.coroutines.experimental.channels.koval.select {
                            channelsListSegments.forEach {
                                ch -> ch.onSend(index) { /* Do nothing */}
                            }
                        }
                    }
                } else {
                    repeat(sendOperations / (consumers * coroutinesMultiplier)) {
                        kotlinx.coroutines.experimental.channels.koval.select {
                            channelsListSegments.forEach {
                                ch -> ch.onReceive { blackhole.consume(it) }
                            }
                        }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
    }

    @Benchmark
    fun mpmcSegments(blackhole: Blackhole) = mpmcSegmentsBase(max(1, threads / 2), max(1, threads / 2), blackhole)

    */

//    @Benchmark
//    fun spmcSegments(blackhole: Blackhole) = mpmcSegmentsBase(1, max(1, threads - 1), blackhole)

//    @Benchmark
//    fun mpscSegments(blackhole: Blackhole) = mpmcSegmentsBase(max(1, threads - 1), 1, blackhole)
}