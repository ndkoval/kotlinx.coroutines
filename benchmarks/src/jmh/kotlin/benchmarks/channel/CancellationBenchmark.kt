package benchmarks.channel

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
open class CancellationBenchmark {
    private val TOTAL_WAITING_COROUTINES = 1_000_000

    @Param(value = IST_SERVER_THREADS)
    private var threads: Int = 0

    @Param
    private var shuffled = false

//    @Param("CURRENT", "SEGMENTS")
    @Param("CURRENT")
    private lateinit var channelCreator: ChannelViewCreator

    private lateinit var ch: ChannelView
    private lateinit var waitingCoroutines: List<Job>

    @Setup
    fun setup() {
        ch = channelCreator.create()
        waitingCoroutines = List(TOTAL_WAITING_COROUTINES) {
            launch { ch.send(it) }
        }
        if (shuffled) waitingCoroutines = waitingCoroutines.shuffled()
    }

    @Benchmark
    fun parallelCancellation() = runBlocking {
        val jobs = List(threads) { id ->
            launch {
                for (i in id until TOTAL_WAITING_COROUTINES step threads) {
                    waitingCoroutines[i].cancel()
                }
                for (i in id until TOTAL_WAITING_COROUTINES step threads) {
                    waitingCoroutines[i].join()
                }
            }
        }
        jobs.forEach { it.join() }
    }
}