package kotlinx.coroutines.experimental.channels.koval

import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectClause2

interface ChannelKoval<E> {
    suspend fun send(element: E)
    val onSend: SelectClause2<E, ChannelKoval<E>>
    fun offer(element: E): Boolean

    suspend fun receive(): E
    val onReceive: SelectClause1<E>
    fun poll(): E?

    fun close(cause: Throwable? = null): Boolean
}