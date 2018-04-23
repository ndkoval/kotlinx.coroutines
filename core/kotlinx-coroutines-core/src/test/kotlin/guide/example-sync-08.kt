package guide.sync.example08

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.sync.Semaphore
import kotlinx.coroutines.experimental.sync.withSemaphore
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.system.measureTimeMillis

suspend fun massiveRun(context: CoroutineContext, action: suspend () -> Unit) {
    val n = 1000 // number of coroutines to launch
    val k = 1000 // times an action is repeated by each coroutine
    val time = measureTimeMillis {
        val jobs = List(n) {
            launch(context) {
                repeat(k) { action() }
            }
        }
        jobs.forEach { it.join() }
    }
    println("Completed ${n * k} actions in $time ms")
}

val semaphore = Semaphore(1)
var counter = 0

fun main(args: Array<String>) = runBlocking {
    massiveRun(CommonPool) {
        semaphore.withSemaphore {
            counter++
        }
    }
    println("Counter = $counter")
}