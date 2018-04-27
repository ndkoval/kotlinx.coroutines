package benchmarks

import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.RendezvousChannel
import kotlinx.coroutines.experimental.channels.koval.ChannelKoval
import kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKoval
import kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKovalMSQueue
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
    fun producerConsumer(blackhole: Blackhole) = runBlocking {
        if (coroutines % contentionFactor * 2 != 0) return@runBlocking
        val jobs = List(coroutines) { index ->
            val channel = channels[index % contentionFactor]
            val sender = (index / contentionFactor) % 2 == 0
            launch(dispatcher) {
                repeat(TOTAL_OPERATIONS / coroutines) {
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
}

internal interface ChannelView {
    suspend fun send(element: Int)
    suspend fun receive(): Int
}

internal enum class ChannelViewCreator(val create: () -> ChannelView) {
    ELIZAROV_RENDEZVOUS({ object : ChannelView {
        val c = RendezvousChannel<Int>()
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>()
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_MS_RENDEZVOUS({ object : ChannelView {
        val c = RendezvousChannelKovalMSQueue<Int>()
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }})
}