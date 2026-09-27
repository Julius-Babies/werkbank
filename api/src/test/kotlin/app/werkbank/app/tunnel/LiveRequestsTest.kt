package app.werkbank.app.tunnel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LiveRequestsTest {

    @Test
    fun `running requests stay listed and completed ones are kept up to the limit`() {
        val live = LiveRequests<String>(maxCompleted = 2)
        val ids = List(5) { Uuid.random() }
        ids.forEachIndexed { i, id -> live.add(id, "r$i") }

        ids.take(4).forEach { live.release(it) }

        // r4 still runs; of the completed ones only the two newest remain.
        assertEquals(listOf("r4", "r2", "r3"), live.items.value)
    }

    @Test
    fun `releasing twice or releasing an unknown request changes nothing`() {
        val live = LiveRequests<String>(maxCompleted = 2)
        val id = Uuid.random()
        live.add(id, "r")

        assertTrue(live.release(id))
        assertFalse(live.release(id))
        assertFalse(live.release(Uuid.random()))
        assertEquals(listOf("r"), live.items.value)
    }

    @Test
    fun `concurrent releases keep the list consistent`() {
        val live = LiveRequests<Uuid>(maxCompleted = 100)
        val ids = List(1000) { Uuid.random() }
        ids.forEach { live.add(it, it) }

        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            // Every request is released from two threads at once, like a finish racing a fail.
            (ids + ids).forEach { id -> pool.execute { start.await(); live.release(id) } }
            start.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }

        val items = live.items.value
        assertEquals(100, items.size)
        assertEquals(100, items.toSet().size)
    }
}
