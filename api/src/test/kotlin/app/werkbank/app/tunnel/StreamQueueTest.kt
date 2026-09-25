package app.werkbank.app.tunnel

import app.werkbank.shared.tunnel.StreamOverflowException
import app.werkbank.shared.tunnel.StreamQueue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamQueueTest {

    private fun queue(maxBytes: Long = 10) = StreamQueue<ByteArray>(maxBytes) { it.size }

    @Test
    fun `offer overflows instead of blocking once the byte budget is used up`() {
        val queue = queue()
        assertTrue(queue.offer(ByteArray(6)))
        assertTrue(queue.offer(ByteArray(4)))
        assertFalse(queue.offer(ByteArray(1)))
    }

    @Test
    fun `consumed items free their budget again`() = runBlocking<Unit> {
        val queue = queue()
        assertTrue(queue.offer(ByteArray(10)))
        queue.close()
        queue.forEach {}
        assertTrue(queue.offer(ByteArray(0)))
        // Closed: dropped, but not an overflow.
        assertTrue(queue.offer(ByteArray(10)))
    }

    @Test
    fun `empty items are accepted even when the budget is used up`() {
        val queue = queue()
        assertTrue(queue.offer(ByteArray(10)))
        assertTrue(queue.offer(ByteArray(0)))
    }

    @Test
    fun `close still delivers queued items in order`() = runBlocking<Unit> {
        val queue = queue()
        queue.offer(byteArrayOf(1))
        queue.offer(byteArrayOf(2))
        queue.close()
        val received = mutableListOf<Byte>()
        queue.forEach { received += it.single() }
        assertEquals(listOf<Byte>(1, 2), received)
    }

    @Test
    fun `cancel drops queued items and surfaces its cause`() = runBlocking<Unit> {
        val queue = queue()
        queue.offer(byteArrayOf(1))
        queue.cancel(StreamOverflowException("overflow"))
        val received = mutableListOf<Byte>()
        assertFailsWith<StreamOverflowException> {
            queue.forEach { received += it.single() }
        }
        assertEquals(emptyList(), received)
    }
}
