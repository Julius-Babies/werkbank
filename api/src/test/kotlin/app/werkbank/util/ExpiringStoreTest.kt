package app.werkbank.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ExpiringStoreTest {

    private class FakeClock : Clock {
        var now = Instant.fromEpochSeconds(0)
        override fun now() = now
        fun advance(duration: Duration) {
            now += duration
        }
    }

    @Test
    fun `returns a stored value until it expires`() {
        val clock = FakeClock()
        val store = ExpiringStore<String, String>(ttl = 10.minutes, maxSize = 10, clock = clock)
        store["a"] = "value"

        clock.advance(10.minutes - 1.seconds)
        assertEquals("value", store["a"])

        clock.advance(1.seconds)
        assertNull(store["a"])
        assertNull(store.remove("a"))
    }

    @Test
    fun `remove returns the value once`() {
        val store = ExpiringStore<String, String>(ttl = 10.minutes, maxSize = 10)
        store["a"] = "value"

        assertEquals("value", store.remove("a"))
        assertNull(store.remove("a"))
        assertNull(store["a"])
    }

    @Test
    fun `evicts expired entries on insert`() {
        val clock = FakeClock()
        val store = ExpiringStore<Int, Int>(ttl = 10.minutes, maxSize = 100, clock = clock)
        repeat(50) { store[it] = it }

        clock.advance(10.minutes)
        store[100] = 100

        assertEquals(1, store.size)
    }

    @Test
    fun `drops the oldest entry when full`() {
        val store = ExpiringStore<Int, Int>(ttl = 10.minutes, maxSize = 3)
        repeat(4) { store[it] = it }

        assertEquals(3, store.size)
        assertNull(store[0])
        assertEquals(3, store[3])
    }

    @Test
    fun `overwriting a key renews its expiry`() {
        val clock = FakeClock()
        val store = ExpiringStore<String, String>(ttl = 10.minutes, maxSize = 10, clock = clock)
        store["a"] = "old"
        clock.advance(5.minutes)
        store["a"] = "new"
        clock.advance(6.minutes)

        assertEquals("new", store["a"])
    }

    @Test
    fun `handles concurrent writers`() = runBlocking {
        val store = ExpiringStore<Int, Int>(ttl = 10.minutes, maxSize = 100_000)
        (0 until 8).map { worker ->
            async(Dispatchers.Default) {
                repeat(1_000) { store[worker * 1_000 + it] = it }
            }
        }.awaitAll()

        assertEquals(8_000, store.size)
    }
}
