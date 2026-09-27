package app.werkbank.util

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Thread-safe in-memory map whose entries expire after [ttl]. Expired entries behave like missing ones and
 * are evicted on every write. Once [maxSize] is reached, the oldest entry is dropped to make room.
 */
class ExpiringStore<K : Any, V : Any>(
    private val ttl: Duration,
    private val maxSize: Int,
    private val clock: Clock = Clock.System,
) {
    private class Entry<V>(val value: V, val expiresAt: Instant)

    // Insertion order equals expiry order since every entry has the same ttl.
    private val entries = LinkedHashMap<K, Entry<V>>()

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    val size: Int
        get() = synchronized(entries) {
            evictExpired(clock.now())
            entries.size
        }

    operator fun set(key: K, value: V) = synchronized(entries) {
        val now = clock.now()
        evictExpired(now)
        entries.remove(key)
        while (entries.size >= maxSize) entries.remove(entries.keys.first())
        entries[key] = Entry(value, now + ttl)
    }

    operator fun get(key: K): V? = synchronized(entries) {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= clock.now()) {
            entries.remove(key)
            return null
        }
        entry.value
    }

    fun remove(key: K): V? = synchronized(entries) {
        val entry = entries.remove(key) ?: return null
        entry.value.takeIf { entry.expiresAt > clock.now() }
    }

    private fun evictExpired(now: Instant) {
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().expiresAt > now) break
            iterator.remove()
        }
    }
}
