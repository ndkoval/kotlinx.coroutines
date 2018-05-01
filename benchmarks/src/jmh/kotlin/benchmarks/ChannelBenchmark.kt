package benchmarks

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

@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
open class ChannelBenchmark {
    private val TOTAL_OPERATIONS = 1_000_000

    @Param("1", "2", "4", "8", "12", "18", "24", "36", "48", "72", "98", "144", "160", "200", "250")
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
    fun producerConsumer(blackhole: Blackhole) = runBlocking {
        if (coroutines % (contentionFactor * 2) != 0) return@runBlocking
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
//    ELIZAROV_RENDEZVOUS({ object : ChannelView {
//        val c = RendezvousChannel<Int>()
//        suspend override fun send(element: Int) = c.send(element)
//        suspend override fun receive(): Int = c.receive()
//    }}),
    KOVAL_RENDEZVOUS_ELIM_1_1_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 1, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_1_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 1, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_1_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 1, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_2_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 2, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_2_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 2, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_2_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 2, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_4_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 4, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_4_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 4, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_4_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 4, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_8_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 8, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_8_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 8, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_8_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 8, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_16_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 16, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_16_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 16, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_1_16_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 1, elimArrSize = 16, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_1_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 1, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_1_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 1, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_1_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 1, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_2_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 2, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_2_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 2, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_2_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 2, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_4_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 4, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_4_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 4, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_4_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 4, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_8_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 8, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_8_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 8, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_8_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 8, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_16_10({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 16, elimSpinThreshold = 10)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_16_50({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 16, elimSpinThreshold = 50)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    KOVAL_RENDEZVOUS_ELIM_5_16_100({ object : ChannelView {
        val c = RendezvousChannelKoval<Int>(elimAttempts = 5, elimArrSize = 16, elimSpinThreshold = 100)
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }})

//    KOVAL_MS_RENDEZVOUS({ object : ChannelView {
//        val c = RendezvousChannelKovalMSQueue<Int>()
//        suspend override fun send(element: Int) = c.send(element)
//        suspend override fun receive(): Int = c.receive()
//    }}),
//    KOVAL_STACK_RENDEZVOUS({ object : ChannelView {
//        val c = RendezvousChannelKovalStack<Int>()
//        suspend override fun send(element: Int) = c.send(element)
//        suspend override fun receive(): Int = c.receive()
//    }})
}