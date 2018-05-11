package benchmarks.channel

import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKoval
import kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKovalMSQueue
import kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKovalStack
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
open class ProducerConsumerBenchmark {
    private val SEND_OPERATIONS = 500_000

    @Param(value = IST_SERVER_THREADS)
    private var threads: Int = 0

//    @Param("2", "10", "100", "1000", "10000")
    @Param("10000")
    private var coroutines: Int = 0

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

    @Benchmark
    fun mpmcBadse(producers: Int, consumers: Int, blackhole: Blackhole) = runBlocking {
        if (coroutines % (contentionFactor * 2) != 0) return@runBlocking
        if (SEND_OPERATIONS % (coroutines * 2) != 0) return@runBlocking

        val jobs = List(coroutines) { index ->
            val channel = channels[index % contentionFactor]
            val sender = (index / contentionFactor) % 2 == 0
            launch(dispatcher) {
                repeat(SEND_OPERATIONS / (coroutines * 2)) {
                    if (sender) {
                        channel.send(index)
                    } else {
                        blackhole.consume(channel.receive())
                    }
                }
            }
        }
        jobs.forEach { it.join() }
    }

    fun mpmcBase(producers: Int, consumers: Int, blackhole: Blackhole) = runBlocking {
        check(SEND_OPERATIONS % (producers * contentionFactor) == 0)
        check(SEND_OPERATIONS % (consumers * contentionFactor) == 0)

        val jobs = List(producers + consumers) { index ->
            val channel = channels[index % contentionFactor]
            val isSender = (index % 2 == 0 && (index / 2 + 1) <= producers) || (index / 2 + 1) > consumers
            launch(dispatcher) {
                if (isSender) {
                    repeat(SEND_OPERATIONS / producers) {
                        channel.send(index)
                    }
                } else {
                    repeat(SEND_OPERATIONS / consumers) {
                        blackhole.consume(channel.receive())
                    }
                }
            }
        }
        jobs.forEach { it.join() }
    }

    @Benchmark
    fun mpmc(blackhole: Blackhole) = mpmcBadse(coroutines / 2, coroutines / 2, blackhole)

    @Benchmark
    fun spmc(blackhole: Blackhole) = mpmcBadse(1, coroutines / 2, blackhole)

    @Benchmark
    fun mpsc(blackhole: Blackhole) = mpmcBadse(coroutines / 2, 1, blackhole)
}