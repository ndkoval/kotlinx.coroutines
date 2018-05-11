package benchmarks.channel

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