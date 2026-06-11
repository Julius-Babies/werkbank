package app.werkbank.app.tunnel

import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.ServerMessage
import app.werkbank.shared.tunnel.base64Chunks
import app.werkbank.shared.tunnel.json
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
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

fun Route.tunnel() {

    val tunnelManager by inject<TunnelManager>()

    authenticate("jwt") {
        webSocket {
            val user = call.principal<UserPrincipal>()!!
            val instance = TunnelInstance(this)
            tunnelManager.onNewIncomingTunnel(user.user, instance)

            runCatching {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        when (val message = json.decodeFromString<ClientMessage>(frame.readText())) {
                            is ClientMessage.HttpResponse -> {
                                val requestId = message.requestId
                                instance.pendingCalls[requestId]?.send(message)
                            }
                            is ClientMessage.HttpBody -> {
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
                        }
                    }
                }
            }.also {
                tunnelManager.onTunnelClosed(user.user)
            }
        }
    }
}

class TunnelInstance(
    val webSocketSession: DefaultWebSocketServerSession,
) {
    typealias RequestId = Uuid
    val pendingCalls = mutableMapOf<RequestId, Channel<ClientMessage>>()
    val wsBridges = mutableMapOf<RequestId, WsBridge>()

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

        webSocketSession.sendSerialized<ServerMessage>(ServerMessage.WsOpen(
            requestId = requestId,
            project = projectName,
            service = serviceName,
            path = path,
            headers = headers.flatMap { (key, values) ->
                values.map { "$key: $it" }
            }
        ))

        val response = channel.receive()

        if (response !is ClientMessage.WsOpened) {
            pendingCalls.remove(requestId)
            channel.close()
            throw IllegalStateException("Expected WsOpened but got $response")
        }

        pendingCalls.remove(requestId)
        channel.close()

        val bridge = WsBridge(requestId, this)
        wsBridges[requestId] = bridge
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

        this.webSocketSession.sendSerialized<ServerMessage>(ServerMessage.HttpRequest(
            requestId = requestId,
            project = projectName,
            service = serviceName,
            path = path,
            method = method.value,
            headers = headers.flatMap { (key, values) ->
                values.map { "$key: $it" }
            }
        ))

        body?.base64Chunks { chunk ->
            this.webSocketSession.sendSerialized<ServerMessage>(ServerMessage.HttpBody(
                requestId = requestId,
                body = chunk
            ))
        }

        this.webSocketSession.sendSerialized<ServerMessage>(ServerMessage.HttpEnd(
            requestId = requestId
        ))

        val responseBodyChannel = ByteChannel()
        val result = CompletableDeferred<Response>()
        coroutineScope.launch {
            for (incoming in incomingChannel) {
                when (incoming) {
                    is ClientMessage.HttpResponse -> result.complete(Response(
                        status = HttpStatusCode.fromValue(incoming.statusCode),
                        headers = incoming.headers
                            .map { it.split(": ", limit = 2) }
                            .map { it.component1() to it.component2() }
                            .groupBy { it.first }
                            .mapValues { it.value.map { it.second } },
                        body = responseBodyChannel
                    ))
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
            }
        }

        return result
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
            is Frame.Binary -> tunnelInstance.sendMessage(ServerMessage.WsBinary(requestId, Base64.encode(frame.readBytes())))
            is Frame.Close -> tunnelInstance.sendMessage(ServerMessage.WsClose(requestId, frame.readReason()?.code?.toInt() ?: 1000, frame.readReason()?.message ?: ""))
            else -> {}
        }
    }

    fun onTunnelMessage(message: ClientMessage) {
        when (message) {
            is ClientMessage.WsText -> _incomingFrames.trySend(Frame.Text(message.text))
            is ClientMessage.WsBinary -> _incomingFrames.trySend(Frame.Binary(true, Base64.decode(message.body)))
            is ClientMessage.WsClose -> close()
            else -> {}
        }
    }

    fun close() {
        _incomingFrames.close()
        tunnelInstance.wsBridges.remove(requestId)
    }
}