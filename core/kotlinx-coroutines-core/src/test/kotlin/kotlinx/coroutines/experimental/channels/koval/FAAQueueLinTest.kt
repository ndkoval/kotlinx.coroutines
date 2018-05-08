package kotlinx.coroutines.experimental.channels.koval

import com.devexperts.dxlab.lincheck.LinChecker
import com.devexperts.dxlab.lincheck.annotations.Operation
import com.devexperts.dxlab.lincheck.annotations.Param
import com.devexperts.dxlab.lincheck.annotations.Reset
import com.devexperts.dxlab.lincheck.paramgen.IntGen
import com.devexperts.dxlab.lincheck.stress.StressCTest
import org.junit.Test

@StressCTest
class FAAQueueLinTest {
    private lateinit var q: FAAQueue<Int>

    @Reset
    fun reset() { q = FAAQueue(2) }

    @Operation
    fun enqueue(@Param(gen = IntGen::class) item: Int) = q.enqueue(item)

    @Operation
    fun deque() = q.dequeue()

    @Test
    fun test() = LinChecker.check(FAAQueueLinTest::class.java)
}