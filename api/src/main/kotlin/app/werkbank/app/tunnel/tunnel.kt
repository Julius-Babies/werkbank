package app.werkbank.app.tunnel

import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.ServerMessage
import app.werkbank.shared.tunnel.TunnelCheckpoint
import app.werkbank.shared.tunnel.TUNNEL_ALREADY_RUNNING_REASON
import app.werkbank.shared.tunnel.TunnelFrame
import app.werkbank.shared.tunnel.json
import app.werkbank.shared.tunnel.rawChunks
import app.werkbank.util.launchConnectionJob
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import org.koin.ktor.ext.inject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

typealias RequestId = Uuid

fun Route.tunnel() {

    val tunnelManager by inject<TunnelManager>()

    authenticate(AUTH_USER_JWT) {
        webSocket {
            val user = call.principal<UserPrincipal>()!!
            val connection = TunnelInstance(this)

            // One tunnel per account: a second one would make it ambiguous which client a proxied
            // request belongs to. Only a live tunnel blocks; a stale one is replaced by tryRegister.
            if (!tunnelManager.tryRegister(user.user, connection)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, TUNNEL_ALREADY_RUNNING_REASON))
                return@webSocket
            }

            launchConnectionJob(call.application, "tunnel-ping") {
                var missedPongs = 0
                while (true) {
                    val pingId = Uuid.random()
                    val startTime = System.currentTimeMillis()
                    val latch = connection.awaitPong(pingId)
                    sendSerialized<ServerMessage>(ServerMessage.Ping(pingId))
                    val ok = withTimeoutOrNull(5.seconds) {
                        latch.await()
                        true
                    } ?: false
                    if (ok) {
                        missedPongs = 0
                        connection.updatePingMs(System.currentTimeMillis() - startTime)
                    } else if (++missedPongs >= TunnelInstance.MAX_MISSED_PONGS) {
                        // Don't leave a dead tunnel sitting on the account's slot until the OS
                        // notices the socket is gone: drop it, so the client can reconnect.
                        call.application.environment.log.info(
                            "Tunnel of user {} missed {} pongs, closing it", user.user.id.value, missedPongs
                        )
                        connection.terminate()
                        break
                    }
                    delay(3.seconds)
                }
            }

            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> {
                            val bytes = frame.readBytes()
                            if (bytes.size < TunnelFrame.HEADER_SIZE) continue
                            // Both HTTP response bodies and WebSocket binary frames arrive as raw binary
                            // frames (see [TunnelFrame]); the flags byte says which, so we route the
                            // payload straight to its sink without any base64 round-trip.
                            val requestId = TunnelFrame.requestId(bytes)
                            val payload = TunnelFrame.payload(bytes)
                            if (TunnelFrame.isWebSocket(bytes)) {
                                connection.dispatchWsBinary(requestId, payload, TunnelFrame.isFin(bytes))
                            } else {
                                connection.dispatchBinary(requestId, payload)
                            }
                        }

                        is Frame.Text -> {
                            when (val message = json.decodeFromString<ClientMessage>(frame.readText())) {
                                is ClientMessage.Ping ->
                                    sendSerialized<ServerMessage>(ServerMessage.Pong(message.requestId))
                                is ClientMessage.Pong -> connection.onPongReceived(message.requestId)
                                else -> connection.dispatch(message)
                            }
                        }

                        else -> {}
                    }
                }
            } catch (e: Exception) {
                println("Tunnel connection closed: ${e.message}")
            } finally {
                tunnelManager.onTunnelClosed(user.user, connection)
            }
        }
    }
}

/**
 * A consumer of the client messages that belong to one request id. The [TunnelInstance] multiplexer
 * routes every incoming [ClientMessage] to the sink registered under [ClientMessage.requestId]; it
 * neither knows nor cares whether that sink is an HTTP [ProxyRequest] or a [WsBridge].
 */
interface MessageSink {
    suspend fun onClientMessage(message: ClientMessage)

    /**
     * Raw binary body bytes routed to this sink's request id (HTTP response body chunks). Defaulted
     * to a no-op because only [ProxyRequest] carries a binary body; a [WsBridge] never receives one.
     */
    suspend fun onBinaryBody(bytes: ByteArray) {}

    /**
     * A raw WebSocket binary frame routed to this sink's request id. Defaulted to a no-op because
     * only [WsBridge] carries WebSocket frames; a [ProxyRequest] never receives one.
     */
    suspend fun onWsBinary(bytes: ByteArray, fin: Boolean) {}

    /** Invoked when the whole tunnel goes away, so the sink can release everyone waiting on it. */
    fun onClosed(cause: Throwable?)
}

/**
 * Something the overview UI can list and observe: an HTTP [ProxyRequest] or a [WsBridge]. Both expose
 * their evolving state as a [snapshot] so consumers can render and stream it uniformly.
 */
sealed interface TrackedRequest {
    val requestId: RequestId
    val snapshot: StateFlow<TunnelRequestRecord>
}

/**
 * One physical tunnel WebSocket, acting purely as a multiplexer: it owns the socket, the ping/pong
 * liveness probe and the routing table from request id to [MessageSink]. The per-request lifecycle
 * (status transitions, response streaming) lives in [ProxyRequest], not here.
 */
class TunnelInstance(
    val webSocketSession: DefaultWebSocketServerSession,
) {
    private val sinks = ConcurrentHashMap<RequestId, MessageSink>()

    private val _requests = MutableStateFlow<List<TrackedRequest>>(emptyList())

    /** All requests seen on this tunnel, in arrival order. Observe each one's own [TrackedRequest.snapshot]. */
    val requests: StateFlow<List<TrackedRequest>> = _requests

    private val _pingMs = MutableStateFlow<Long?>(null)
    val pingMs: StateFlow<Long?> = _pingMs

    @Volatile
    private var lastPongAt: Long = System.currentTimeMillis()

    fun updatePingMs(value: Long) {
        _pingMs.value = value
    }

    /**
     * Whether this tunnel is still usable. False once the socket's scope is gone, or once the client
     * stopped answering pings for [STALE_AFTER_MS] — a half-open TCP connection stays `isActive` for
     * as long as the OS keeps it around, so the pong timestamp is the only reliable liveness signal.
     */
    val isAlive: Boolean
        get() = webSocketSession.isActive && System.currentTimeMillis() - lastPongAt < STALE_AFTER_MS

    /** Registers a new outgoing HTTP request and returns its live handle. Call [ProxyRequest.send] to fire it. */
    fun startRequest(record: TunnelRequestRecord, scope: CoroutineScope): ProxyRequest {
        val request = ProxyRequest(record, this, scope)
        sinks[record.requestId] = request
        _requests.update { it + request }
        return request
    }

    /** Routes an incoming client message to whatever sink owns its request id. */
    suspend fun dispatch(message: ClientMessage) {
        sinks[message.requestId]?.onClientMessage(message)
    }

    /** Routes raw binary body bytes (an HTTP response body chunk) to the sink owning [requestId]. */
    suspend fun dispatchBinary(requestId: RequestId, bytes: ByteArray) {
        sinks[requestId]?.onBinaryBody(bytes)
    }

    /** Routes a raw WebSocket binary frame to the sink owning [requestId]. */
    suspend fun dispatchWsBinary(requestId: RequestId, bytes: ByteArray, fin: Boolean) {
        sinks[requestId]?.onWsBinary(bytes, fin)
    }

    internal fun unregister(requestId: RequestId) {
        sinks.remove(requestId)
    }

    suspend fun send(message: ServerMessage) {
        webSocketSession.sendSerialized<ServerMessage>(message)
    }

    suspend fun sendBinary(requestId: RequestId, bytes: ByteArray, flags: Int = 0) {
        webSocketSession.send(Frame.Binary(true, TunnelFrame.encode(requestId, flags, bytes)))
    }

    /** Registers a WebSocket proxy connection, performs the open handshake and returns its live bridge. */
    suspend fun startWsProxy(record: TunnelRequestRecord): WsBridge {
        val bridge = WsBridge(record, this)
        sinks[record.requestId] = bridge
        _requests.update { it + bridge }

        send(
            ServerMessage.WsOpen(
                requestId = record.requestId,
                project = record.projectName,
                service = record.serviceName,
                path = record.uri,
                headers = record.requestHeaders.toHeaderLines(),
            )
        )

        val responseHeaderLines = withTimeoutOrNull(30.seconds) { bridge.awaitOpened() }
        if (responseHeaderLines == null) {
            bridge.close()
            throw TunnelClosedException("WebSocket proxy timed out waiting for client")
        }
        bridge.markEstablished(responseHeaderLines)
        return bridge
    }

    private val pingLock = Any()
    private var pendingPingId: Uuid? = null
    private var pendingPingLatch: CompletableDeferred<Unit>? = null

    fun awaitPong(requestId: Uuid): CompletableDeferred<Unit> {
        synchronized(pingLock) {
            pendingPingId = requestId
            val latch = CompletableDeferred<Unit>()
            pendingPingLatch = latch
            return latch
        }
    }

    fun onPongReceived(requestId: Uuid) {
        synchronized(pingLock) {
            if (requestId == pendingPingId) {
                lastPongAt = System.currentTimeMillis()
                pendingPingLatch?.complete(Unit)
            }
        }
    }

    fun close() {
        sinks.values.forEach { it.onClosed(TunnelClosedException()) }
        sinks.clear()
    }

    /**
     * Hard-kills the socket and everything riding on it. Used for a tunnel that no longer answers,
     * where a graceful [DefaultWebSocketServerSession.close] would just block on the dead connection.
     * Cancelling the session ends its reader loop, which unregisters the tunnel on its way out.
     */
    fun terminate() {
        close()
        webSocketSession.cancel()
    }

    companion object {
        /** No pong for this long means the client is gone, whatever the socket still claims. */
        private const val STALE_AFTER_MS = 15_000L

        /** Consecutive unanswered pings before the server tears the tunnel down itself. */
        const val MAX_MISSED_PONGS = 3
    }
}

/**
 * The live handle for a single HTTP request flowing through a tunnel. Once [send] is called it owns
 * the whole downstream lifecycle: it consumes the client's messages, drives the status transitions
 * on its own [snapshot] and streams the response body. The tunnel only feeds it messages.
 */
class ProxyRequest internal constructor(
    initial: TunnelRequestRecord,
    private val connection: TunnelInstance,
    private val scope: CoroutineScope,
) : MessageSink, TrackedRequest {

    override val requestId: RequestId = initial.requestId

    private val _snapshot = MutableStateFlow(initial)

    /** The observable state of this request; every phase transition emits a new value. */
    override val snapshot: StateFlow<TunnelRequestRecord> = _snapshot

    // Control messages and raw body chunks share one FIFO so the response body stays ordered relative
    // to the http.response header message and the http.end that closes the stream. Buffered so the
    // tunnel's reader loop isn't forced into a suspend handoff with the consumer on every body chunk.
    private val inbox = Channel<Inbound>(capacity = 64)
    private val responseBodyChannel = ByteChannel()
    private val response = CompletableDeferred<TunnelResponse>()

    private sealed interface Inbound {
        data class Control(val message: ClientMessage) : Inbound
        class Body(val bytes: ByteArray) : Inbound
    }

    /** Sends the request (headers, body, end) over the tunnel and starts consuming the response. */
    suspend fun send(body: ByteReadChannel?) {
        val snap = _snapshot.value
        connection.send(
            ServerMessage.HttpRequest(
                requestId = requestId,
                project = snap.projectName,
                service = snap.serviceName,
                path = snap.uri,
                method = snap.method,
                headers = snap.requestHeaders.toHeaderLines(),
            )
        )

        // Bodyless requests skip the http.end frame: the host only uses it to close the request-body
        // channel, which it never creates for bodyless methods — one JSON frame saved per GET.
        if (body != null) {
            body.rawChunks { connection.sendBinary(requestId, it) }
            connection.send(ServerMessage.HttpEnd(requestId))
        }
        _snapshot.update { it.copy(sentToTunnelAt = System.currentTimeMillis()) }

        scope.launch { consume() }
    }

    /** Suspends until response headers arrive; throws [TimeoutException]/[ServerNotRunningException]/[TunnelClosedException]. */
    suspend fun awaitResponse(): TunnelResponse = response.await()

    override suspend fun onClientMessage(message: ClientMessage) {
        inbox.send(Inbound.Control(message))
    }

    override suspend fun onBinaryBody(bytes: ByteArray) {
        inbox.send(Inbound.Body(bytes))
    }

    override fun onClosed(cause: Throwable?) {
        inbox.close(cause ?: TunnelClosedException())
    }

    /**
     * Records a terminal failure that originates outside the tunnel — e.g. the response could not be
     * streamed to the browser. Idempotent: an already-recorded error/completion is kept.
     */
    fun fail(cause: Throwable) {
        if (!response.isCompleted) response.completeExceptionally(cause)
        responseBodyChannel.close(cause)
        _snapshot.update {
            it.copy(
                error = it.error ?: cause.message ?: cause::class.simpleName ?: "Request failed",
                completedAt = it.completedAt ?: System.currentTimeMillis(),
            )
        }
        connection.unregister(requestId)
        inbox.close()
    }

    private suspend fun consume() {
        try {
            for (inbound in inbox) {
                when (inbound) {
                    is Inbound.Body -> {
                        responseBodyChannel.writeFully(inbound.bytes)
                        responseBodyChannel.flush()
                    }

                    is Inbound.Control -> when (val message = inbound.message) {
                        is ClientMessage.RequestResolved ->
                            _snapshot.update { it.copy(serviceName = message.service) }

                        is ClientMessage.Timeout -> throw TimeoutException()
                        is ClientMessage.ServerNotRuning -> throw ServerNotRunningException()

                        is ClientMessage.UnexpectedError -> {
                            _snapshot.update {
                                it.copy(
                                    error = it.error ?: message.message,
                                    checkpoints = message.checkpoints,
                                )
                            }
                            throw UnexpectedTunnelException(message.message, message.checkpoints)
                        }

                        is ClientMessage.HttpResponse -> onResponse(message)

                        is ClientMessage.HttpEnd -> {
                            responseBodyChannel.flushAndClose()
                            finish()
                        }

                        else -> {}
                    }
                }
            }
        } catch (e: CancellationException) {
            fail(e)
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun onResponse(message: ClientMessage.HttpResponse) {
        val headers = message.headers
            .map { it.split(": ", limit = 2) }
            .filter { it.size == 2 }
            .groupBy({ it[0] }, { it[1] })
        _snapshot.update {
            it.copy(
                responseStartedAt = System.currentTimeMillis(),
                statusCode = message.statusCode,
                responseHeaders = headers,
            )
        }
        response.complete(
            TunnelResponse(
                status = HttpStatusCode.fromValue(message.statusCode),
                headers = headers,
                body = responseBodyChannel,
            )
        )
    }

    private fun finish() {
        _snapshot.update { it.copy(completedAt = it.completedAt ?: System.currentTimeMillis()) }
        connection.unregister(requestId)
        inbox.close()
    }
}

/**
 * The live handle for a proxied WebSocket connection. Every frame passes through here in both
 * directions ([send] browser→dev-server, [onClientMessage] dev-server→browser), so this is also the
 * capture point for the inspector: each frame is appended to the frame log and counted on [snapshot].
 */
class WsBridge internal constructor(
    initial: TunnelRequestRecord,
    private val connection: TunnelInstance,
) : MessageSink, TrackedRequest {

    override val requestId: RequestId = initial.requestId

    private val _snapshot = MutableStateFlow(initial)
    override val snapshot: StateFlow<TunnelRequestRecord> = _snapshot

    private val _incomingFrames = Channel<Frame>(Channel.UNLIMITED)
    val incomingFrames: ReceiveChannel<Frame> = _incomingFrames

    private val opened = CompletableDeferred<List<String>>()

    /**
     * Whether the tunnel host already knows this connection is closing, either because the browser's
     * Close frame was relayed or because the host itself closed it. Guards [relayClientGone] so an
     * abort never produces a second close.
     */
    private val closeRelayed = AtomicBoolean(false)

    /** Suspends until the client confirms the upstream socket is open; returns the 101 response header lines. */
    suspend fun awaitOpened(): List<String> = opened.await()

    private val frameLock = Any()
    private val _frames = mutableListOf<WsFrameRecord>()
    private val _frameEvents = MutableSharedFlow<WsFrameRecord>(extraBufferCapacity = 256)

    /** New frames as they are captured. Combine with [framesSnapshot] for a gap-free replay-then-live view. */
    val frameEvents: SharedFlow<WsFrameRecord> = _frameEvents

    fun framesSnapshot(): List<WsFrameRecord> = synchronized(frameLock) { _frames.toList() }

    /** Buffer for a fragmented binary message from the dev server; see [onWsBinary]. */
    private var pendingBinary: ByteArrayOutputStream? = null

    fun markEstablished(responseHeaderLines: List<String>) {
        val headers = responseHeaderLines
            .map { it.split(": ", limit = 2) }
            .filter { it.size == 2 }
            .groupBy({ it[0] }, { it[1] })
        _snapshot.update {
            it.copy(
                statusCode = 101,
                responseHeaders = headers,
                sentToTunnelAt = it.sentToTunnelAt ?: System.currentTimeMillis(),
                responseStartedAt = it.responseStartedAt ?: System.currentTimeMillis(),
            )
        }
    }

    private fun record(
        direction: WsFrameDirection,
        opcode: WsFrameOpcode,
        text: String?,
        binaryBase64: String?,
        size: Int,
        closeCode: Int? = null,
        closeReason: String? = null,
    ) {
        val frame = synchronized(frameLock) {
            if (_frames.size >= MAX_FRAMES) return
            WsFrameRecord(
                sequence = _frames.size,
                direction = direction,
                opcode = opcode,
                text = text,
                binaryBase64 = binaryBase64,
                size = size,
                timestamp = System.currentTimeMillis(),
                closeCode = closeCode,
                closeReason = closeReason,
            ).also { _frames.add(it) }
        }
        _snapshot.update {
            when (direction) {
                WsFrameDirection.CLIENT_TO_SERVER -> it.copy(wsFramesSent = it.wsFramesSent + 1)
                WsFrameDirection.SERVER_TO_CLIENT -> it.copy(wsFramesReceived = it.wsFramesReceived + 1)
            }
        }
        _frameEvents.tryEmit(frame)
    }

    /** Browser → dev server. */
    suspend fun send(frame: Frame) {
        when (frame) {
            is Frame.Text -> {
                val text = frame.readText()
                record(WsFrameDirection.CLIENT_TO_SERVER, WsFrameOpcode.TEXT, text, null, text.encodeToByteArray().size)
                connection.send(ServerMessage.WsText(requestId, text))
            }

            is Frame.Binary -> {
                val bytes = frame.readBytes()
                // Base64 here is only for the stored inspector record (its column is text); the frame
                // itself goes to the tunnel as raw bytes.
                record(WsFrameDirection.CLIENT_TO_SERVER, WsFrameOpcode.BINARY, null, Base64.encode(bytes), bytes.size)
                connection.sendBinary(requestId, bytes, TunnelFrame.webSocketFlags(frame.fin))
            }

            is Frame.Close -> {
                val reason = frame.readReason()
                val code = reason?.code?.toInt() ?: 1000
                val message = reason?.message ?: ""
                closeRelayed.set(true)
                record(WsFrameDirection.CLIENT_TO_SERVER, WsFrameOpcode.CLOSE, null, null, 0, code, message)
                connection.send(ServerMessage.WsClose(requestId, code, message))
            }

            else -> {}
        }
    }

    /**
     * Tells the tunnel host that the browser side is gone after a hard abort — a TCP reset, a closed
     * tab or a ping timeout, i.e. every path where the browser never sends a Close frame.
     *
     * A close message is the only signal the host listens for, so without this its upstream WebSocket
     * to the local dev server would stay open for the rest of the tunnel's lifetime and its frames
     * would be routed to a request id that no longer has a sink. No-op once a close was relayed.
     */
    suspend fun relayClientGone() {
        if (!closeRelayed.compareAndSet(false, true)) return
        record(WsFrameDirection.CLIENT_TO_SERVER, WsFrameOpcode.CLOSE, null, null, 0, GOING_AWAY, CLIENT_GONE_REASON)
        try {
            connection.send(ServerMessage.WsClose(requestId, GOING_AWAY, CLIENT_GONE_REASON))
        } catch (_: Exception) {
            // The tunnel itself is already gone; the host will tear the upstream down on its own.
        }
    }

    /** Dev server → browser. */
    override suspend fun onClientMessage(message: ClientMessage) {
        when (message) {
            is ClientMessage.WsOpened -> opened.complete(message.headers)

            is ClientMessage.WsText -> {
                record(WsFrameDirection.SERVER_TO_CLIENT, WsFrameOpcode.TEXT, message.text, null, message.text.encodeToByteArray().size)
                _incomingFrames.trySend(Frame.Text(message.text))
            }

            is ClientMessage.WsClose -> {
                // The host closed it itself, so it needs no close back from us.
                closeRelayed.set(true)
                record(WsFrameDirection.SERVER_TO_CLIENT, WsFrameOpcode.CLOSE, null, null, 0, message.code, message.reason)
                // A close before the open handshake completed means the upstream refused the connection;
                // surface its reason so the proxy can report why instead of a generic failure.
                if (!opened.isCompleted) {
                    opened.completeExceptionally(
                        TunnelClosedException("Upstream refused WebSocket (${message.code}): ${message.reason}")
                    )
                }
                _incomingFrames.trySend(Frame.Close(CloseReason(message.code.toShort(), message.reason)))
                close()
            }

            else -> {}
        }
    }

    /**
     * Dev server → browser: a raw WebSocket binary frame.
     *
     * Fragments (`fin = false`) are buffered until the FIN arrives and then relayed as one message.
     * Forwarding them one by one would be wrong on the wire: ktor writes every [Frame.Binary] with
     * the BINARY opcode, never a continuation, so the browser would see a second message start
     * mid-message and fail the connection. Reassembling here also keeps the inspector timeline at one
     * record per message.
     */
    override suspend fun onWsBinary(bytes: ByteArray, fin: Boolean) {
        // Called only from the tunnel's single reader loop, so the buffer needs no synchronization.
        if (!fin) {
            pendingBinary = (pendingBinary ?: ByteArrayOutputStream()).apply { write(bytes) }
            return
        }
        val message = pendingBinary?.let { buffer ->
            buffer.write(bytes)
            pendingBinary = null
            buffer.toByteArray()
        } ?: bytes

        // Base64 only for the stored inspector record; the frame relays to the browser as raw bytes.
        record(WsFrameDirection.SERVER_TO_CLIENT, WsFrameOpcode.BINARY, null, Base64.encode(message), message.size)
        _incomingFrames.trySend(Frame.Binary(true, message))
    }

    override fun onClosed(cause: Throwable?) {
        if (cause != null) _snapshot.update { it.copy(error = it.error ?: cause.message) }
        close()
    }

    fun close() {
        if (!opened.isCompleted) opened.completeExceptionally(TunnelClosedException())
        _snapshot.update { it.copy(completedAt = it.completedAt ?: System.currentTimeMillis()) }
        _incomingFrames.close()
        connection.unregister(requestId)
    }

    companion object {
        /** Safety cap on how many frames a single connection retains for the inspector. */
        private const val MAX_FRAMES = 2000

        /** RFC 6455 close code for "the endpoint is going away", used when the browser aborted. */
        private const val GOING_AWAY = 1001
        private const val CLIENT_GONE_REASON = "Client disconnected without a close frame"
    }
}

data class TunnelResponse(
    val status: HttpStatusCode,
    val headers: Map<String, List<String>>,
    val body: ByteReadChannel?,
)

class TimeoutException : Exception("Request timed out")
class ServerNotRunningException : Exception("Service not running")
class TunnelClosedException(message: String? = null) : Exception(message ?: "Tunnel connection closed")

/** The tunnel host reported an unexpected error, carrying the checkpoints the request reached. */
class UnexpectedTunnelException(
    message: String,
    val checkpoints: List<TunnelCheckpoint>,
) : Exception(message)

private fun Map<String, List<String>>.toHeaderLines(): List<String> =
    flatMap { (key, values) -> values.map { "$key: $it" } }
