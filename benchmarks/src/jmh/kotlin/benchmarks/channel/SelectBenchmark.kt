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

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
open class SelectBenchmark {
    private val SEND_OPERATIONS = 500_000

    //    @Param("1", "2", "4", "8", "12", "16", "20", "30", "40", "60", "80") // dxLab server
    @Param("1", "2", "4", "8", "12", "18", "24", "36", "48", "72", "98", "144", "160", "200", "250") // IST server
    private var threads: Int = 0

//    @Param("2", "10", "100", "1000", "10000")
    @Param("10000")
    private var coroutines: Int = 0

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
        check(SEND_OPERATIONS % producers == 0)
        check(SEND_OPERATIONS % consumers == 0)

        val jobs = List(producers + consumers) { index ->
            val sender = (index % 2 == 0 && (index / 2 + 1) <= producers) || (index / 2 + 1) > consumers
            launch(dispatcher) {
                if (sender) {
                    repeat(SEND_OPERATIONS / producers) {
                        kotlinx.coroutines.experimental.selects.select {
                            channelsListCurrent.forEach {
                                ch -> ch.onSend(index) { /* Do nothing */}
                            }
                        }
                    }
                } else {
                    repeat(SEND_OPERATIONS / consumers) {
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
    fun mpmcCurrent(blackhole: Blackhole) = mpmcCurrentBase(coroutines / 2, coroutines / 2, blackhole)

    @Benchmark
    fun spmcCurrent(blackhole: Blackhole) = mpmcCurrentBase(1, coroutines, blackhole)

    @Benchmark
    fun mpscCurrent(blackhole: Blackhole) = mpmcCurrentBase(coroutines, 1, blackhole)



    fun mpmcSegmentsBase(producers: Int, consumers: Int, blackhole: Blackhole) = runBlocking {
        check(SEND_OPERATIONS % producers == 0)
        check(SEND_OPERATIONS % consumers == 0)

        val jobs = List(producers + consumers) { index ->
            val isSender = (index % 2 == 0 && (index / 2 + 1) <= producers) || (index / 2 + 1) > consumers
            launch(dispatcher) {
                if (isSender) {
                    repeat(SEND_OPERATIONS / producers) {
                        kotlinx.coroutines.experimental.channels.koval.select {
                            channelsListSegments.forEach {
                                ch -> ch.onSend(index) { /* Do nothing */}
                            }
                        }
                    }
                } else {
                    repeat(SEND_OPERATIONS / consumers) {
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
    fun mpmcSegments(blackhole: Blackhole) = mpmcSegmentsBase(coroutines / 2, coroutines / 2, blackhole)

    @Benchmark
    fun spmcSegments(blackhole: Blackhole) = mpmcSegmentsBase(1, coroutines, blackhole)

    @Benchmark
    fun mpscSegments(blackhole: Blackhole) = mpmcSegmentsBase(coroutines, 1, blackhole)
}