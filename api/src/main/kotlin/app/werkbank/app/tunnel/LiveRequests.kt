package app.werkbank.app.tunnel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The requests of one tunnel that the overview lists live: every running one plus the [maxCompleted]
 * most recently completed ones, so a dashboard that connects right after a request finished still sees
 * it. Older ones are dropped; they are persisted and served from the database.
 */
internal class LiveRequests<T : Any>(private val maxCompleted: Int) {
    private val lock = Any()
    private val running = LinkedHashMap<RequestId, T>()
    private val completed = ArrayDeque<T>()

    private val _items = MutableStateFlow<List<T>>(emptyList())

    /** The running requests in arrival order, followed by the completed ones in completion order. */
    val items: StateFlow<List<T>> = _items

    fun add(requestId: RequestId, item: T) = synchronized(lock) {
        running[requestId] = item
        publish()
    }

    /** Moves [requestId] to the completed ones. Idempotent; returns whether it was still running. */
    fun release(requestId: RequestId): Boolean = synchronized(lock) {
        val item = running.remove(requestId) ?: return false
        completed.addLast(item)
        if (completed.size > maxCompleted) completed.removeFirst()
        publish()
        true
    }

    // Bounded by the running requests plus maxCompleted, not by everything the tunnel ever proxied.
    private fun publish() {
        _items.value = ArrayList<T>(running.size + completed.size).apply {
            addAll(running.values)
            addAll(completed)
        }
    }
}
