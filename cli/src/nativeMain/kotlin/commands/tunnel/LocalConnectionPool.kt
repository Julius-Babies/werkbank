package commands.tunnel

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.utils.io.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Keep-alive pool for HTTP/1.1 connections to local dev services.
 *
 * Dialing a new TCP connection per proxied request is pure per-request latency, so cleanly finished
 * connections are parked here and handed out again. Only bodyless requests may take a pooled
 * connection: the peer can close an idle connection at any moment, and a request without a body can
 * simply be resent on a fresh one — a streamed request body is already consumed and cannot.
 */
class LocalConnectionPool(private val selectorManager: SelectorManager) {

    class Lease internal constructor(
        internal val key: String,
        private val socket: Socket,
        val input: ByteReadChannel,
        val output: ByteWriteChannel,
        /** True when this lease reuses a pooled connection, i.e. a stale-connection retry may be needed. */
        val reused: Boolean,
        /** True when the connection may return to the pool after a cleanly framed response. */
        val poolable: Boolean,
    ) {
        internal var idleSince: Instant = Clock.System.now()

        fun close() {
            socket.close()
        }

        internal fun again(): Lease = Lease(key, socket, input, output, reused = true, poolable = true)
    }

    private val mutex = Mutex()
    private val idle = mutableMapOf<String, ArrayDeque<Lease>>()

    /**
     * Hands out a connection to `host:port`. With [poolable] set, an idle pooled connection is reused
     * when available (unless [forceFresh] skips the pool, e.g. for a retry after a stale reuse).
     */
    suspend fun acquire(
        host: String,
        port: Int,
        useTls: Boolean,
        tlsContext: CoroutineContext,
        poolable: Boolean,
        forceFresh: Boolean = false,
    ): Lease {
        val key = "$host:$port"
        if (poolable && !forceFresh) {
            val pooled = mutex.withLock {
                val queue = idle[key]
                val now = Clock.System.now()
                var candidate = queue?.removeLastOrNull()
                // Drop connections the local server has likely timed out while they sat idle
                // (node's default keep-alive timeout is 5s); reusing those only buys a retry.
                while (candidate != null && now - candidate.idleSince >= IDLE_TTL) {
                    candidate.close()
                    candidate = queue?.removeLastOrNull()
                }
                candidate
            }
            if (pooled != null) return pooled.again()
        }
        val raw = aSocket(selectorManager).tcp().connect(host, port)
        val socket = if (useTls) raw.tls(coroutineContext = tlsContext) else raw
        return Lease(
            key = key,
            socket = socket,
            input = socket.openReadChannel(),
            output = socket.openWriteChannel(autoFlush = false),
            reused = false,
            poolable = poolable,
        )
    }

    /** Returns a cleanly finished connection to the pool for the next request to the same target. */
    suspend fun release(lease: Lease) {
        lease.idleSince = Clock.System.now()
        mutex.withLock {
            val queue = idle.getOrPut(lease.key) { ArrayDeque() }
            if (queue.size >= MAX_IDLE_PER_TARGET) {
                lease.close()
            } else {
                queue.addLast(lease)
            }
        }
    }

    companion object {
        private val IDLE_TTL = 3.seconds
        private const val MAX_IDLE_PER_TARGET = 8
    }
}
