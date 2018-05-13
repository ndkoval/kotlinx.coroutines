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
    }})
}


internal fun gcd(a: Int, b: Int): Int {
    if (b == 0) return a
    return gcd(b, a % b)
}

internal fun lcm(a: Int, b: Int): Int {
    return a / gcd(a, b) * b
}