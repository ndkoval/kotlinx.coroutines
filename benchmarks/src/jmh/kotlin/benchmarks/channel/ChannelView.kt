package benchmarks.channel

internal val IST_SERVER_THREADS = arrayOf("1", "2", "4", "8", "12", "18", "24", "36", "48", "72", "98", "144", "160", "200", "250")
internal val DXLAB_SERVER_THREADS = arrayOf("1", "2", "4", "8", "12", "16", "20", "30", "40", "60", "80")



internal interface ChannelView {
    suspend fun send(element: Int)
    suspend fun receive(): Int
}

internal enum class ChannelViewCreator(val create: () -> ChannelView) {
    CURRENT({ object : ChannelView {
        val c = kotlinx.coroutines.experimental.channels.RendezvousChannel<Int>()
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    SEGMENTS({ object : ChannelView {
        val c = kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKoval<Int>()
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    MSQUEUE({ object : ChannelView {
        val c = kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKovalMSQueue<Int>()
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }}),
    STACK({ object : ChannelView {
        val c = kotlinx.coroutines.experimental.channels.koval.RendezvousChannelKovalStack<Int>()
        suspend override fun send(element: Int) = c.send(element)
        suspend override fun receive(): Int = c.receive()
    }});
}