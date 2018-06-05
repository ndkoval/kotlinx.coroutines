package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.koval.SelectBuilder.Companion.NOT_SELECTED
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.suspendAtomicCancellableCoroutine
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext


fun main(args: Array<String>) = runBlocking {
    val ch1 = RendezvousChannelKoval<Int>()
    val ch2 = RendezvousChannelKoval<Int>()
    val ch3 = RendezvousChannelKoval<Int>()


    select<Unit> {
        ch1.onReceive { value ->

        }
        ch2.onReceive { value ->

        }
        ch3.onSend(1) {

        }
    }
}


suspend inline fun <R> select(crossinline builder: SelectBuilder<R>.() -> Unit): R {
    val select = SelectBuilderImpl<R>()
    builder(select)
    // Try select without suspension at first
    val res = select.trySelect()
    if (res != NOT_SELECTED) return res as R
    // Should be suspended
    return suspendAtomicCancellableCoroutine(holdCancellability = true) { cont ->
        select.cont = cont
        select.selectSuspend(cont)
    }
}

/**
 * Clause for [select] expression without additional parameters that selects value of type [Q].
 */
interface SelectClause1<out Q> {
    fun <R> tryPerformSelectClause1(select: SelectInstance<R>, block: suspend (Q) -> R): Any

    /**
     * Registers this clause with the specified [select] instance and [block] of code.
     * @suppress **This is unstable API and it is subject to change.**
     */
    fun <R> registerSelectClause1(select: SelectInstance<R>, block: suspend (Q) -> R)
}

/**
 * Clause for [select] expression with additional parameter of type [P] that selects value of type [Q].
 */
interface SelectClause2<in P, out Q> {
    fun <R> tryPerformSelectClause2(select: SelectInstance<R>, param: P, block: suspend (Q) -> R): Any

    /**
     * Registers this clause with the specified [select] instance and [block] of code.
     * @suppress **This is unstable API and it is subject to change.**
     */
    fun <R> registerSelectClause2(select: SelectInstance<R>, param: P, block: suspend (Q) -> R)
}

interface SelectInstance<in R> {
    val completion: Continuation<R>
    fun performSelect(node: RendezvousChannelKoval.Node): Boolean
}

/**
 * Scope for [select] invocation.
 */
interface SelectBuilder<in R> {
    /**
     * Try to perform select without suspension
     */
    fun trySelect(): Any?

    fun <R> selectSuspend(cont: CancellableContinuation<R>)

    /**
     * Registers clause in this [select] expression without additional parameters that selects value of type [Q].
     */
    operator fun <Q> SelectClause1<Q>.invoke(block: suspend (Q) -> R)

    /**
     * Registers clause in this [select] expression with additional parameter of type [P] that selects value of type [Q].
     */
    operator fun <P, Q> SelectClause2<P, Q>.invoke(param: P, block: suspend (Q) -> R)

    /**
     * Registers clause in this [select] expression with additional parameter nullable parameter of type [P]
     * with the `null` value for this parameter that selects value of type [Q].
     */
    operator fun <P, Q> SelectClause2<P?, Q>.invoke(block: suspend (Q) -> R) = invoke(null, block)

    companion object {
        val NOT_SELECTED = Symbol("NOT_SELECTED")
    }
}

enum class Action { TRY_SELECT, SUSPENDED_SELECT }

class SelectBuilderImpl<in R> : SelectBuilder<R>, SelectInstance<R>, Continuation<R> {
    override val completion: Continuation<R>
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
    lateinit var cont: CancellableContinuation<*>

    override val context: CoroutineContext
        get() = cont.context

    override fun resume(value: R) {
        (cont as CancellableContinuation<R>).resumeDirect(value)
    }

    override fun resumeWithException(exception: Throwable) {
        (cont as CancellableContinuation<R>).resumeDirectWithException(exception)
    }

    private val trySelectClauses = arrayListOf<() -> R>()
    private val suspendSelectClauses = arrayListOf<() -> Unit>()

    @Volatile private var _result: Any? = null // null -> [node] -> result

    override fun trySelect(): Any? {
        for (c in trySelectClauses) {
            val res = c()
            if (res !== NOT_SELECTED) return res
        }
        return NOT_SELECTED
    }

    override fun <R> selectSuspend(cont: CancellableContinuation<R>) {
        for (c in suspendSelectClauses) {
            c()
            if (_result != null) {
                completePerformSelect()
                return
            }
        }
        cont.initCancellability()
    }

    override fun performSelect(node: RendezvousChannelKoval.Node): Boolean {
        val result = _result
        return when (result) {
            null -> {
                val selected = resultUpdater.compareAndSet(this, null, node)
                if (selected || node === _result) completePerformSelect()
                selected
            }
            SELECTED -> false
            else -> { // node
                if (node === result) completePerformSelect()
                false
            }
        }
    }

    private fun completePerformSelect() {
        val node = _result as? RendezvousChannelKoval.Node ?: return
        val dequeIndex = node._deqIdx
        if (node.getContinuationWeak(dequeIndex) != this) return // moved forward already
        RendezvousChannelKoval.deqIdxUpdater.compareAndSet(node, dequeIndex, dequeIndex + 1)
        _result = SELECTED
    }

    override fun <Q> SelectClause1<Q>.invoke(block: suspend (Q) -> R) {
//        trySelectClauses += { tryPerformSelectClause1(this@SelectBuilderImpl, block) }
        suspendSelectClauses += { registerSelectClause1(this@SelectBuilderImpl, block) }
    }

    override fun <P, Q> SelectClause2<P, Q>.invoke(param: P, block: suspend (Q) -> R) {
//        trySelectClauses += { tryPerformSelectClause2(this@SelectBuilderImpl, param, block) }
        suspendSelectClauses += { registerSelectClause2(this@SelectBuilderImpl, param, block) }
    }

    companion object {
        @JvmField val SELECTED = Symbol("SELECTED")
        @JvmField val resultUpdater = AtomicReferenceFieldUpdater.newUpdater(SelectBuilderImpl::class.java, Any::class.java, "_result")
    }
}