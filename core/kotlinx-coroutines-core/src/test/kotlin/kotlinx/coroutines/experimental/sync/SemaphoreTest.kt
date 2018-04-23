package kotlinx.coroutines.experimental.sync

import kotlinx.coroutines.experimental.*
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemaphoreTest : TestBase() {

    @Test(timeout = 100)
    fun simpleTest() = runBlocking {
        val semaphore = Semaphore(maxPermits = 2)
        expect(1)
        launch(coroutineContext) {
            expect(4)
            semaphore.acquire()
            expect(5)
            yield()
            expect(9)
            semaphore.release()
            expect(10)
        }
        launch(coroutineContext) {
            expect(6)
            semaphore.acquire()
            expect(11)
            semaphore.release()
            expect(12)
        }
        expect(2)
        semaphore.acquire()
        expect(3)
        yield()
        expect(7)
        semaphore.release()
        expect(8)
        yield()
        finish(13)
    }

    @Test
    fun tryAcquireTest() {
        val semaphore = Semaphore(maxPermits = 2)
        assertEquals(2, semaphore.availablePermits)
        assertTrue(semaphore.tryAcquire())
        assertEquals(1, semaphore.availablePermits)
        assertTrue(semaphore.tryAcquire())
        assertEquals(0, semaphore.availablePermits)
        assertFalse(semaphore.tryAcquire())
        assertEquals(0, semaphore.availablePermits)
        semaphore.release()
        assertEquals(1, semaphore.availablePermits)
        assertTrue(semaphore.tryAcquire())
        semaphore.release()
        semaphore.release()
        assertEquals(2, semaphore.availablePermits)
    }

    @Test
    fun withSemaphoreTest() = runBlocking {
        val semaphore = Semaphore(maxPermits = 1)
        assertEquals(1, semaphore.availablePermits)
        semaphore.withSemaphore {
            assertEquals(0, semaphore.availablePermits)
        }
        assertEquals(1, semaphore.availablePermits)
    }

    @Test
    fun semaphoreAsLockStressTest() = runBlocking {
        val n = 1000 * stressTestMultiplier
        val k = 100
        var shared = 0
        val semaphore = Semaphore(maxPermits = 1)
        val jobs = List(k) {
            launch(CommonPool) {
                repeat(n) {
                    semaphore.acquire()
                    shared++
                    semaphore.release()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(n * k, shared)
    }

    @Test
    fun semaphoreStressTest() = runBlocking {
        // TODO
    }

    @Ignore
    @Test
    fun testUnconfinedStackOverflow() = runBlocking {
        val waiters = 10000
        val semaphore = Semaphore(1)
        semaphore.acquire()
        var done = 0
        repeat(waiters) {
            launch(Unconfined) {  // a lot of unconfined waiters
                semaphore.withSemaphore {
                    done++
                }
            }
        }
        semaphore.release() // should not produce StackOverflowError
        assertEquals(waiters, done)
    }

    @Test(timeout = 100)
    fun testCancellation() = runBlocking {
        val semaphore = Semaphore(maxPermits = 1)
        expect(1)
        val jobToCancel = launch(coroutineContext) {
            expect(4)
            semaphore.acquire()
            semaphore.release()
        }
        launch(coroutineContext) {
            expect(5)
            semaphore.acquire()
            expect(7)
            semaphore.release()
        }
        expect(2)
        semaphore.acquire()
        expect(3)
        yield()
        expect(6)
        jobToCancel.cancel()
        semaphore.release()
        yield()
        finish(8)
    }

    @Test(expected = IllegalStateException::class)
    fun testReleaseWithoutAcquireFails() {
        val semaphore = Semaphore(maxPermits = 1)
        semaphore.release()
    }
}