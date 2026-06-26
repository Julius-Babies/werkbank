package commands.tunnel

import app.config.MainConfig
import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.ServerMessage
import app.werkbank.shared.tunnel.json
import app.werkbank.shared.tunnel.rawChunks
import http.httpClient
import http.isServiceNotRunningException
import http.isTimeoutException
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.io.encoding.Base64
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TunnelViewModel: KoinComponent {

    companion object {
        val TUNNEL_RECONNECT_DELAY = 5.seconds
    }

    private val mainConfig by inject<MainConfig>()
    private val tunnelRequestResolver = TunnelRequestResolver()
    private val client = httpClient()

    private val authToken: String

    val state: StateFlow<TunnelState>
        field = MutableStateFlow(TunnelState())

    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests

    private val viewModelScope = CoroutineScope(Dispatchers.Default)

    init {
        val authTokenValue = mainConfig.getConfig().auth?.bearer
        if (authTokenValue == null) {
            println(buildStyledString {
                red { +"You are not logged into your Werkbank cloud account." }
                +" "
                +"Use "
                blue { +"wb login" }
                +" to log in."
            })
            exitProcess(1)
        }
        authToken = authTokenValue

        viewModelScope.launch {
            while (true) {
                state.update { it.copy(connectionState = TunnelState.ConnectionState.Connecting) }

                val requestBodies = mutableMapOf<Uuid, ByteWriteChannel>()
                val wsProxyState = mutableMapOf<Uuid, DefaultClientWebSocketSession>()

                try {
                    client.webSocket(
                        urlString = "wss://${mainConfig.getConfig().werkbankCloudDomain}/api/tunnel",
                        request = {
                            bearerAuth(authToken)
                        }
                    ) serverSession@{
                        var currentPingId: Uuid? = null
                        var lastPingStart: Instant? = null
                        var currentPingLatch = CompletableDeferred(Unit)
                        launch {
                            while (this@serverSession.isActive) {
                                lastPingStart = Clock.System.now()
                                currentPingId = Uuid.random()
                                sendSerialized<ClientMessage>(ClientMessage.Ping(currentPingId))
                                currentPingLatch = CompletableDeferred()
                                withTimeout(15.seconds) {
                                    currentPingLatch.await()
                                }
                                state.update { it.copy(connectionState = TunnelState.ConnectionState.Connected(currentPing = Clock.System.now() - lastPingStart)) }
                                delay(500.milliseconds)
                            }
                        }
                        state.update { it.copy(connectionState = TunnelState.ConnectionState.Connected(currentPing = null)) }

                        for (message in incoming) {
                            when (message) {
                                is Frame.Binary -> {
                                    val bytes = message.readBytes()
                                    if (bytes.size < 16) continue
                                    val id = Uuid.fromByteArray(bytes.copyOfRange(0, 16))
                                    requestBodies[id]?.writeFully(bytes.copyOfRange(16, bytes.size))
                                    requestBodies[id]?.flush()
                                }
                                is Frame.Text -> {
                                    when (val msg = json.decodeFromString<ServerMessage>(message.readText())) {
                                        is ServerMessage.HttpRequest -> {
                                            if (msg.method != "GET") {
                                                requestBodies[msg.requestId] = ByteChannel(autoFlush = true)
                                            }

                                            launch {
                                                val channel = requestBodies[msg.requestId]
                                                val target = tunnelRequestResolver.getTarget(
                                                    projectKey = msg.project,
                                                    serviceKey = msg.service,
                                                    path = msg.path,
                                                    isWebsocket = false,
                                                ) ?: return@launch

                                                _requests.update { it + Request(
                                                    requestId = msg.requestId,
                                                    method = msg.method,
                                                    project = target.project.id,
                                                    service = target.service.name,
                                                    path = msg.path,
                                                    targetUrl = target.url,
                                                    startedAt = Clock.System.now(),
                                                    result = null
                                                ) }

                                                val response = try {
                                                    client.prepareRequest(
                                                        urlString = target.url,
                                                    ) {
                                                        method = HttpMethod(msg.method)
                                                        msg.headers.forEach { header ->
                                                            val (key, value) = header.split(": ", limit = 2)
                                                            headers.append(key, value)
                                                        }
                                                        if (channel != null) setBody(channel)
                                                    }.execute()
                                                } catch (e: Exception) {
                                                    if (e.isTimeoutException()) {
                                                        _requests.update { list -> list.map { if (it.requestId == msg.requestId) it.copy(result = Request.Result.Timeout(Clock.System.now())) else it } }
                                                        sendSerialized<ClientMessage>(ClientMessage.Timeout(requestId = msg.requestId))
                                                        return@launch
                                                    }

                                                    if (e.isServiceNotRunningException()) {
                                                        _requests.update { list -> list.map { if (it.requestId == msg.requestId) it.copy(result = Request.Result.ServiceNotRunning(Clock.System.now())) else it } }
                                                        sendSerialized<ClientMessage>(ClientMessage.ServerNotRuning(msg.requestId))
                                                        return@launch
                                                    }
                                                    return@launch
                                                }

                                                sendSerialized<ClientMessage>(ClientMessage.HttpResponse(
                                                    requestId = msg.requestId,
                                                    statusCode = response.status.value,
                                                    headers = response.headers.entries().flatMap { (key, values) ->
                                                        values.map { "$key: $it" }
                                                    }
                                                ))

                                                response.bodyAsChannel().rawChunks { rawBytes ->
                                                    val frameData = ByteArray(16 + rawBytes.size)
                                                    msg.requestId.toByteArray().copyInto(frameData)
                                                    rawBytes.copyInto(frameData, 16)
                                                    this@serverSession.send(Frame.Binary(true, frameData))
                                                }

                                                sendSerialized<ClientMessage>(ClientMessage.HttpEnd(
                                                    requestId = msg.requestId
                                                ))

                                                _requests.update { list -> list.map { if (it.requestId == msg.requestId) it.copy(result = Request.Result.Success(Clock.System.now(), response.status.value)) else it } }
                                            }
                                        }
                                        is ServerMessage.HttpBody -> {
                                            val bodyBytes = Base64.decode(msg.body)
                                            requestBodies[msg.requestId]?.writeFully(bodyBytes)
                                            requestBodies[msg.requestId]?.flush()
                                        }
                                        is ServerMessage.HttpEnd -> {
                                            requestBodies[msg.requestId]?.flushAndClose()
                                            requestBodies.remove(msg.requestId)
                                        }
                                        is ServerMessage.WsOpen -> {
                                            launch {
                                                val target = tunnelRequestResolver.getTarget(
                                                    projectKey = msg.project,
                                                    serviceKey = msg.service,
                                                    path = msg.path,
                                                    isWebsocket = true,
                                                ) ?: return@launch

//                                                printRequest(
//                                                    method = "WEBSOCKET",
//                                                    projectKey = target.project.id,
//                                                    serviceKey = target.service.name,
//                                                    path = msg.path,
//                                                    targetUrl = target.url,
//                                                )

                                                client.webSocket(urlString = target.url) {
                                                    wsProxyState[msg.requestId] = this
                                                    this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsOpened(
                                                        requestId = msg.requestId
                                                    ))

                                                    for (frame in incoming) {
                                                        when (frame) {
                                                            is Frame.Text -> this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsText(
                                                                requestId = msg.requestId,
                                                                text = frame.readText()
                                                            ))
                                                            is Frame.Binary -> this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsBinary(
                                                                requestId = msg.requestId,
                                                                body = Base64.encode(frame.readBytes())
                                                            ))
                                                            is Frame.Close -> {
                                                                this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsClose(
                                                                    requestId = msg.requestId,
                                                                    code = frame.readReason()?.code?.toInt() ?: 1000,
                                                                    reason = frame.readReason()?.message ?: ""
                                                                ))
                                                                break
                                                            }
                                                            else -> {}
                                                        }
                                                    }
                                                }

                                                wsProxyState.remove(msg.requestId)
                                            }
                                        }
                                        is ServerMessage.WsText -> {
                                            wsProxyState[msg.requestId]?.send(Frame.Text(msg.text))
                                        }
                                        is ServerMessage.WsBinary -> {
                                            wsProxyState[msg.requestId]?.send(Frame.Binary(true, Base64.decode(msg.body)))
                                        }
                                        is ServerMessage.WsClose -> {
                                            wsProxyState[msg.requestId]?.close(CloseReason(msg.code.toShort(), msg.reason))
                                            wsProxyState.remove(msg.requestId)
                                        }
                                        is ServerMessage.Pong -> {
                                            require(currentPingId == msg.requestId)
                                            currentPingLatch.complete(Unit)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    state.update { it.copy(connectionState = TunnelState.ConnectionState.Retrying(Clock.System.now() + TUNNEL_RECONNECT_DELAY, e)) }
                    delay(TUNNEL_RECONNECT_DELAY)
                }
            }
        }
    }

    fun onCancel() {
        viewModelScope.cancel()
    }
}

data class TunnelState(
    val connectionState: ConnectionState = ConnectionState.Connecting,
) {
    sealed class ConnectionState {
        data class Connected(val currentPing: Duration?): ConnectionState()
        data object Connecting: ConnectionState()
        data class Retrying(val waitUntil: Instant, val throwable: Throwable): ConnectionState()
    }
}

data class Request(
    val requestId: Uuid,
    val method: String,
    val project: String,
    val service: String,
    val path: String,
    val targetUrl: String,
    val startedAt: Instant,
    val result: Result?,
) {
    sealed class Result {
        abstract val finishedAt: Instant

        data class Success(
            override val finishedAt: Instant,
            val statusCode: Int,
        ): Result()

        data class Timeout(override val finishedAt: Instant): Result()
        data class ServiceNotRunning(override val finishedAt: Instant): Result()
    }
}