package kotlinx.coroutines.experimental.sync

import com.devexperts.dxlab.lincheck.LinChecker
import com.devexperts.dxlab.lincheck.annotations.Operation
import com.devexperts.dxlab.lincheck.annotations.Reset
import com.devexperts.dxlab.lincheck.stress.StressCTest
import org.junit.Test

@StressCTest
abstract class AbstractSemaphoreLinearizabilityTest {
    private lateinit var semaphore: AbstractSemaphore
    private var counter: Int = 0

    internal abstract fun newSemaphore(): AbstractSemaphore

    @Reset
    fun reset() {
        semaphore = newSemaphore()
        counter = 0
    }

    @Operation
    fun incAndGet(): Int {
        semaphore.acquireSpinWait()
        try {
            return counter++
        } finally {
            semaphore.releaseSpinWait()
        }
    }

    @Test
    fun test() = LinChecker.check(this.javaClass)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AbstractSemaphoreLinearizabilityTest

        if (counter != other.counter) return false

        return true
    }

    override fun hashCode(): Int {
        return counter
    }
}