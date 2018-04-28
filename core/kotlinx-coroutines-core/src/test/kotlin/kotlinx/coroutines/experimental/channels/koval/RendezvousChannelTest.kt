package kotlinx.coroutines.experimental.channels.koval

/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import kotlinx.coroutines.experimental.TestBase
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.yield
import org.junit.Assert
import org.junit.Test

class RendezvousChannelTest : TestBase() {
    @Test
    fun testSimple() = runBlocking {
        val q = RendezvousChannelKoval<Int>()
//        check(q.isEmpty && q.isFull)
        expect(1)
        val sender = launch(coroutineContext) {
            expect(4)
            q.send(1) // suspend -- the first to come to rendezvous
            expect(7)
            q.send(2) // does not suspend -- receiver is there
            expect(8)
        }
        expect(2)
        val receiver = launch(coroutineContext) {
            expect(5)
            check(q.receive() == 1) // does not suspend -- sender was there
            expect(6)
            check(q.receive() == 2) // suspends
            expect(9)
        }
        expect(3)
        sender.join()
        receiver.join()
//        check(q.isEmpty && q.isFull)
        finish(10)
    }

    @Test
    fun testStress() = runBlocking {
        val n = 100_000
        val q = RendezvousChannelKoval<Int>()
        val sender = launch(coroutineContext) {
            for (i in 1..n) q.send(i)
            expect(2)
        }
        val receiver = launch(coroutineContext) {
            for (i in 1..n) check(q.receive() == i)
            expect(3)
        }
        expect(1)
        sender.join()
        receiver.join()
        finish(4)
    }

    @Test
    fun testOfferAndPool() = runBlocking {
        val q = RendezvousChannelKoval<Int>()
        Assert.assertFalse(q.offer(1))
        expect(1)
        launch(coroutineContext) {
            expect(3)
            Assert.assertEquals(null, q.poll())
            expect(4)
            Assert.assertEquals(2, q.receive())
            expect(7)
            Assert.assertEquals(null, q.poll())
            yield()
            expect(9)
            Assert.assertEquals(3, q.poll())
            expect(10)
        }
        expect(2)
        yield()
        expect(5)
        Assert.assertTrue(q.offer(2))
        expect(6)
        yield()
        expect(8)
        q.send(3)
        finish(11)
    }
}