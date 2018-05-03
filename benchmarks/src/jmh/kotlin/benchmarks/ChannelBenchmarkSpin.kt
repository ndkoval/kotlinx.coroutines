package benchmarks

import kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKoval
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
open class ChannelBenchmarkSpin {
    private val TOTAL_OPERATIONS = 1_000_000

    @Param("2", "4", "8", "12", "18", "24", "36", "48", "72", "98", "144", "160", "200", "250")
    private var threads: Int = 0

    private val ch = RendezvousChannelKoval<Int>()

    @Benchmark
    fun producerConsumer(blackhole: Blackhole) {
        val localWork = TOTAL_OPERATIONS / threads
        val threads = (1 .. threads).map { id ->
            thread {
                if (id % 2 == 0) {
                    repeat(localWork) { ch.sendSpin(id) }
                } else {
                    repeat(localWork) { ch.receiveSpin() }
                }
            }
        }
        threads.forEach { it.join() }
    }
}