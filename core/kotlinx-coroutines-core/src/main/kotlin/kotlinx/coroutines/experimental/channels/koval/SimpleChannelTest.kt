package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.classSimpleName
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking

fun main(args: Array<String>) = runBlocking {
    val TOTAL_WORK = 10_000_000
    val COROUTINES = 10000

    check(TOTAL_WORK % COROUTINES == 0)
    check(COROUTINES % 2 == 0)

    val LOCAL_WORK = TOTAL_WORK / COROUTINES

    val work: (ChannelKoval<Int>) -> Unit = { ch ->
        val start = System.currentTimeMillis()
        runBlocking {
            List(COROUTINES) { id ->
                launch {
                    if (id % 2 == 0) {
                        repeat(LOCAL_WORK) { ch.send(id) }
                    } else {
                        repeat(LOCAL_WORK) { ch.receive() }
                    }
                }
            }.forEach { it.join() }
        }
        val time = System.currentTimeMillis() - start
        println("${ch.classSimpleName}: $time ms")
    }

    val workHard: (() -> ChannelKoval<Int>) -> Unit = { chCreator ->
        repeat(10) { work(chCreator()) }
    }

    workHard { RendezvousChannelKovalMSQueue() }
    workHard { RendezvousChannelKovalStack() }
    workHard { RendezvousChannelKoval() }
}