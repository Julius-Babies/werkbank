package app.werkbank.app.tunnel

import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.ServerMessage
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import org.koin.ktor.ext.inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

typealias RequestId = Uuid

fun Route.tunnel() {

    val tunnelManager by inject<TunnelManager>()

    authenticate(AUTH_USER_JWT) {
        webSocket {
            val user = call.principal<UserPrincipal>()!!
            val connection = TunnelInstance(this)
            tunnelManager.onNewIncomingTunnel(user.user, connection)

            launchConnectionJob(call.application, "tunnel-ping") {
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
                        connection.updatePingMs(System.currentTimeMillis() - startTime)
                    }
                    delay(3.seconds)
                }
            }

            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> {
                            val bytes = frame.readBytes()
                            if (bytes.size < 16) continue
                            val requestId = Uuid.fromByteArray(bytes.copyOfRange(0, 16))
                            connection.dispatch(
                                ClientMessage.HttpBody(
                                    requestId = requestId,
                                    body = Base64.encode(bytes.copyOfRange(16, bytes.size)),
                                )
                            )
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
                tunnelManager.onTunnelClosed(user.user)
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

    /** Invoked when the whole tunnel goes away, so the sink can release everyone waiting on it. */
    fun onClosed(cause: Throwable?)
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

    private val _requests = MutableStateFlow<List<ProxyRequest>>(emptyList())

    /** All requests seen on this tunnel, in arrival order. Observe each one's own [ProxyRequest.snapshot]. */
    val requests: StateFlow<List<ProxyRequest>> = _requests

    private val _pingMs = MutableStateFlow<Long?>(null)
    val pingMs: StateFlow<Long?> = _pingMs

    fun updatePingMs(value: Long) {
        _pingMs.value = value
    }

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

    internal fun unregister(requestId: RequestId) {
        sinks.remove(requestId)
    }

    suspend fun send(message: ServerMessage) {
        webSocketSession.sendSerialized<ServerMessage>(message)
    }

    suspend fun sendBinary(requestId: RequestId, bytes: ByteArray) {
        val frameData = ByteArray(16 + bytes.size)
        requestId.toByteArray().copyInto(frameData)
        bytes.copyInto(frameData, 16)
        webSocketSession.send(Frame.Binary(true, frameData))
    }

    suspend fun wsProxy(
        projectName: String,
        serviceName: String?,
        path: String,
        headers: Map<String, List<String>>,
    ): WsBridge {
        val requestId: RequestId = Uuid.random()
        val bridge = WsBridge(requestId, this)
        sinks[requestId] = bridge

        send(
            ServerMessage.WsOpen(
                requestId = requestId,
                project = projectName,
                service = serviceName,
                path = path,
                headers = headers.toHeaderLines(),
            )
        )

        val opened = withTimeoutOrNull(30.seconds) { bridge.awaitOpened() }
        if (opened == null) {
            bridge.close()
            throw TunnelClosedException("WebSocket proxy timed out waiting for client")
        }
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
                pendingPingLatch?.complete(Unit)
            }
        }
    }

    fun close() {
        sinks.values.forEach { it.onClosed(TunnelClosedException()) }
        sinks.clear()
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
) : MessageSink {

    val requestId: RequestId = initial.requestId

    private val _snapshot = MutableStateFlow(initial)

    /** The observable state of this request; every phase transition emits a new value. */
    val snapshot: StateFlow<TunnelRequestRecord> = _snapshot

    private val inbox = Channel<ClientMessage>()
    private val responseBodyChannel = ByteChannel()
    private val response = CompletableDeferred<TunnelResponse>()

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

        body?.rawChunks { connection.sendBinary(requestId, it) }

        connection.send(ServerMessage.HttpEnd(requestId))
        _snapshot.update { it.copy(sentToTunnelAt = System.currentTimeMillis()) }

        scope.launch { consume() }
    }

    /** Suspends until response headers arrive; throws [TimeoutException]/[ServerNotRunningException]/[TunnelClosedException]. */
    suspend fun awaitResponse(): TunnelResponse = response.await()

    override suspend fun onClientMessage(message: ClientMessage) {
        inbox.send(message)
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
            for (message in inbox) {
                when (message) {
                    is ClientMessage.RequestResolved ->
                        _snapshot.update { it.copy(serviceName = message.service) }

                    is ClientMessage.Timeout -> throw TimeoutException()
                    is ClientMessage.ServerNotRuning -> throw ServerNotRunningException()

                    is ClientMessage.HttpResponse -> onResponse(message)

                    is ClientMessage.HttpBody -> {
                        responseBodyChannel.writeFully(Base64.decode(message.body))
                        responseBodyChannel.flush()
                    }

                    is ClientMessage.HttpEnd -> {
                        responseBodyChannel.flushAndClose()
                        finish()
                    }

                    else -> {}
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

class WsBridge(
    val requestId: RequestId,
    private val connection: TunnelInstance,
) : MessageSink {
    private val _incomingFrames = Channel<Frame>(Channel.UNLIMITED)
    val incomingFrames: ReceiveChannel<Frame> = _incomingFrames

    private val opened = CompletableDeferred<Unit>()

    /** Suspends until the client confirms the upstream socket is open. */
    suspend fun awaitOpened() = opened.await()

    suspend fun send(frame: Frame) {
        when (frame) {
            is Frame.Text -> connection.send(ServerMessage.WsText(requestId, frame.readText()))
            is Frame.Binary -> connection.send(
                ServerMessage.WsBinary(
                    requestId = requestId,
                    fin = frame.fin,
                    body = Base64.encode(frame.readBytes())
                )
            )

            is Frame.Close -> connection.send(
                ServerMessage.WsClose(
                    requestId,
                    frame.readReason()?.code?.toInt() ?: 1000,
                    frame.readReason()?.message ?: ""
                )
            )

            else -> {}
        }
    }

    override suspend fun onClientMessage(message: ClientMessage) {
        when (message) {
            is ClientMessage.WsOpened -> opened.complete(Unit)
            is ClientMessage.WsText -> _incomingFrames.trySend(Frame.Text(message.text))
            is ClientMessage.WsBinary -> _incomingFrames.trySend(Frame.Binary(true, Base64.decode(message.body)))
            is ClientMessage.WsClose -> {
                _incomingFrames.trySend(Frame.Close(CloseReason(message.code.toShort(), message.reason)))
                close()
            }
            else -> {}
        }
    }

    override fun onClosed(cause: Throwable?) {
        close()
    }

    fun close() {
        if (!opened.isCompleted) opened.completeExceptionally(TunnelClosedException())
        _incomingFrames.close()
        connection.unregister(requestId)
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

private fun Map<String, List<String>>.toHeaderLines(): List<String> =
    flatMap { (key, values) -> values.map { "$key: $it" } }
