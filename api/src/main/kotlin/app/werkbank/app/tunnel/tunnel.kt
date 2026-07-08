package app.werkbank.app.tunnel

import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.ServerMessage
import app.werkbank.shared.tunnel.json
import app.werkbank.shared.tunnel.rawChunks
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.koin.ktor.ext.inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

fun Route.tunnel() {

    val tunnelManager by inject<TunnelManager>()

    authenticate(AUTH_USER_JWT) {
        webSocket {
            val user = call.principal<UserPrincipal>()!!
            val instance = TunnelInstance(this)
            tunnelManager.onNewIncomingTunnel(user.user, instance)

            launch {
                while (true) {
                    val pingId = Uuid.random()
                    val startTime = System.currentTimeMillis()
                    val latch = instance.awaitPong(pingId)
                    sendSerialized<ServerMessage>(ServerMessage.Ping(pingId))
                    val ok = withTimeoutOrNull(5.seconds) {
                        latch.await()
                        true
                    } ?: false
                    if (ok) {
                        instance.currentPingMs = System.currentTimeMillis() - startTime
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
                            instance.pendingCalls[requestId]?.send(
                                ClientMessage.HttpBody(
                                    requestId = requestId,
                                    body = Base64.encode(bytes.copyOfRange(16, bytes.size))
                                )
                            )
                        }

                        is Frame.Text -> {
                            when (val message = json.decodeFromString<ClientMessage>(frame.readText())) {
                                is ClientMessage.HttpResponse -> {
                                    val requestId = message.requestId
                                    instance.pendingCalls[requestId]?.send(message)
                                }

                                is ClientMessage.HttpBody -> {
                                    val requestId = message.requestId
                                    instance.pendingCalls[requestId]?.send(message)
                                }

                                is ClientMessage.Timeout -> {
                                    val requestId = message.requestId
                                    instance.pendingCalls[requestId]?.send(message)
                                }

                                is ClientMessage.ServerNotRuning -> {
                                    val requestId = message.requestId
                                    instance.pendingCalls[requestId]?.send(message)
                                }

                                is ClientMessage.HttpEnd -> {
                                    val requestId = message.requestId
                                    instance.pendingCalls[requestId]?.send(message)
                                }

                                is ClientMessage.WsOpened -> {
                                    val requestId = message.requestId
                                    instance.pendingCalls[requestId]?.send(message)
                                }

                                is ClientMessage.WsText -> {
                                    instance.wsBridges[message.requestId]?.onTunnelMessage(message)
                                }

                                is ClientMessage.WsBinary -> {
                                    instance.wsBridges[message.requestId]?.onTunnelMessage(message)
                                }

                                is ClientMessage.WsClose -> {
                                    instance.wsBridges[message.requestId]?.onTunnelMessage(message)
                                }
                                is ClientMessage.Ping -> {
                                    sendSerialized<ServerMessage>(ServerMessage.Pong(message.requestId))
                                }

                                is ClientMessage.Pong -> {
                                    instance.onPongReceived(message.requestId)
                                }
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

class TunnelInstance(
    val webSocketSession: DefaultWebSocketServerSession,
) {
    typealias RequestId = Uuid

    val pendingCalls = ConcurrentHashMap<RequestId, Channel<ClientMessage>>()
    val wsBridges = ConcurrentHashMap<RequestId, WsBridge>()

    @Volatile
    var currentPingMs: Long? = null

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

    suspend fun sendMessage(message: ServerMessage) {
        webSocketSession.sendSerialized<ServerMessage>(message)
    }

    suspend fun wsProxy(
        projectName: String,
        serviceName: String?,
        path: String,
        headers: Map<String, List<String>>,
    ): WsBridge {
        val requestId: RequestId = Uuid.random()

        val channel = Channel<ClientMessage>()
        pendingCalls[requestId] = channel

        webSocketSession.sendSerialized<ServerMessage>(
            ServerMessage.WsOpen(
            requestId = requestId,
            project = projectName,
            service = serviceName,
            path = path,
            headers = headers.flatMap { (key, values) ->
                values.map { "$key: $it" }
            }
        ))

        val response = withTimeoutOrNull(30.seconds) {
            channel.receive()
        }

        if (response == null) {
            pendingCalls.remove(requestId)
            channel.close()
            throw TunnelClosedException("WebSocket proxy timed out waiting for client")
        }

        if (response !is ClientMessage.WsOpened) {
            pendingCalls.remove(requestId)
            channel.close()
            throw IllegalStateException("Expected WsOpened but got $response")
        }

        val bridge = WsBridge(requestId, this)
        wsBridges[requestId] = bridge
        pendingCalls.remove(requestId)
        channel.close()
        return bridge
    }

    suspend fun request(
        method: HttpMethod,
        projectName: String,
        serviceName: String?,
        path: String,
        headers: Map<String, List<String>>,
        body: ByteReadChannel?,
        coroutineScope: CoroutineScope,
    ): Deferred<Response> {
        val requestId: RequestId = Uuid.random()

        val incomingChannel = Channel<ClientMessage>()
        pendingCalls[requestId] = incomingChannel

        this.webSocketSession.sendSerialized<ServerMessage>(
            ServerMessage.HttpRequest(
            requestId = requestId,
            project = projectName,
            service = serviceName,
            path = path,
            method = method.value,
            headers = headers.flatMap { (key, values) ->
                values.map { "$key: $it" }
            }
        ))

        body?.rawChunks { rawBytes ->
            val frameData = ByteArray(16 + rawBytes.size)
            requestId.toByteArray().copyInto(frameData)
            rawBytes.copyInto(frameData, 16)
            webSocketSession.send(Frame.Binary(true, frameData))
        }

        this.webSocketSession.sendSerialized<ServerMessage>(
            ServerMessage.HttpEnd(
                requestId = requestId
            )
        )

        val responseBodyChannel = ByteChannel()
        val result = CompletableDeferred<Response>()
        coroutineScope.launch {
            for (incoming in incomingChannel) {
                try {
                    when (incoming) {
                        is ClientMessage.Timeout -> throw TimeoutException()
                        is ClientMessage.ServerNotRuning -> throw ServerNotRunningException()
                        is ClientMessage.HttpResponse -> result.complete(
                            Response(
                                status = HttpStatusCode.fromValue(incoming.statusCode),
                                headers = incoming.headers
                                    .map { it.split(": ", limit = 2) }
                                    .map { it.component1() to it.component2() }
                                    .groupBy { it.first }
                                    .mapValues { it.value.map { it.second } },
                                body = responseBodyChannel
                            )
                        )

                        is ClientMessage.HttpBody -> {
                            val bytes = Base64.decode(incoming.body)
                            responseBodyChannel.writeFully(bytes)
                            responseBodyChannel.flush()
                        }

                        is ClientMessage.HttpEnd -> {
                            responseBodyChannel.flushAndClose()
                            pendingCalls[requestId]?.close()
                            pendingCalls.remove(requestId)
                        }

                        else -> {}
                    }
                } catch (e: Exception) {
                    pendingCalls[requestId]?.close()
                    pendingCalls.remove(requestId)
                    result.completeExceptionally(e)
                }
            }
        }

        return result
    }

    fun close() {
        this.pendingCalls.values.forEach {
            it.close(TunnelClosedException())
        }
        this.wsBridges.values.toList().forEach {
            it.close()
        }
    }

    data class Response(
        val status: HttpStatusCode,
        val headers: Map<String, List<String>>,
        val body: ByteReadChannel?,
    )
}

class WsBridge(
    val requestId: Uuid,
    private val tunnelInstance: TunnelInstance,
) {
    private val _incomingFrames = Channel<Frame>(Channel.UNLIMITED)
    val incomingFrames: ReceiveChannel<Frame> = _incomingFrames

    suspend fun send(frame: Frame) {
        when (frame) {
            is Frame.Text -> tunnelInstance.sendMessage(ServerMessage.WsText(requestId, frame.readText()))
            is Frame.Binary -> tunnelInstance.sendMessage(
                ServerMessage.WsBinary(
                    requestId = requestId,
                    fin = frame.fin,
                    body = Base64.encode(frame.readBytes())
                )
            )

            is Frame.Close -> tunnelInstance.sendMessage(
                ServerMessage.WsClose(
                    requestId,
                    frame.readReason()?.code?.toInt() ?: 1000,
                    frame.readReason()?.message ?: ""
                )
            )

            else -> {}
        }
    }

    fun onTunnelMessage(message: ClientMessage) {
        when (message) {
            is ClientMessage.WsText -> _incomingFrames.trySend(Frame.Text(message.text))
            is ClientMessage.WsBinary -> _incomingFrames.trySend(Frame.Binary(true, Base64.decode(message.body)))
            is ClientMessage.WsClose -> {
                _incomingFrames.trySend(Frame.Close(CloseReason(message.code.toShort(), message.reason)))
                close()
            }
            else -> {}
        }
    }

    fun close() {
        _incomingFrames.close()
        tunnelInstance.wsBridges.remove(requestId)
    }
}

class TimeoutException : Exception()
class ServerNotRunningException : Exception()
class TunnelClosedException(message: String? = null) : Exception(message)