package commands.tunnel

import app.config.MainConfig
import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.ServerMessage
import app.werkbank.shared.tunnel.json
import app.werkbank.shared.tunnel.rawChunks
import http.httpClientBase
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.network.tls.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
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
    private val client = httpClientBase {
        followRedirects = false
        install(WebSockets) {
            pingInterval = 15.seconds
            contentConverter = KotlinxWebsocketSerializationConverter(json)
        }

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    private val authToken: String

    val state: StateFlow<TunnelState>
        field = MutableStateFlow(TunnelState())

    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests

    private val selectorManager = SelectorManager(Dispatchers.Default)

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
                                val ok = withTimeoutOrNull(15.seconds) {
                                    currentPingLatch.await()
                                    true
                                } ?: false
                                if (ok) {
                                    state.update { it.copy(connectionState = TunnelState.ConnectionState.Connected(currentPing = Clock.System.now() - lastPingStart)) }
                                }
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

                                                sendSerialized<ClientMessage>(ClientMessage.RequestResolved(
                                                    requestId = msg.requestId,
                                                    service = target.service.name,
                                                ))

                                                _requests.update { it + Request(
                                                    requestId = msg.requestId,
                                                    method = msg.method,
                                                    project = target.project.id,
                                                    service = target.service.name,
                                                    path = msg.path,
                                                    targetUrl = target.url,
                                                    startedAt = Clock.System.now(),
                                                    headers = msg.headers.map { header ->
                                                        val (key, value) = header.split(": ")
                                                        key to value
                                                    },
                                                    result = null
                                                ) }

                                                val targetUrl = URLBuilder(target.url).build()
                                                val isHttps = targetUrl.protocol.name.equals("https", ignoreCase = true)
                                                val host = targetUrl.host
                                                val port = targetUrl.port
                                                val path = targetUrl.fullPath

                                                val socket = try {
                                                    val raw = aSocket(selectorManager).tcp().connect(host, port)
                                                    if (isHttps) raw.tls(coroutineContext = currentCoroutineContext()) else raw
                                                } catch (e: Exception) {
                                                    val isTimeout = e.message?.contains("timed out", ignoreCase = true) == true
                                                    if (isTimeout) {
                                                        _requests.update { list -> list.map { if (it.requestId == msg.requestId) it.copy(result = Request.Result.Timeout(Clock.System.now())) else it } }
                                                        sendSerialized<ClientMessage>(ClientMessage.Timeout(requestId = msg.requestId))
                                                    } else {
                                                        _requests.update { list -> list.map { if (it.requestId == msg.requestId) it.copy(result = Request.Result.ServiceNotRunning(Clock.System.now())) else it } }
                                                        sendSerialized<ClientMessage>(ClientMessage.ServerNotRuning(msg.requestId))
                                                    }
                                                    return@launch
                                                }

                                                val input = socket.openReadChannel()
                                                val output = socket.openWriteChannel(autoFlush = true)

                                                val requestLine = "${msg.method} $path HTTP/1.1\r\n"
                                                output.writeFully(requestLine.encodeToByteArray())

                                                val authority = if (port == targetUrl.protocol.defaultPort) host else "$host:$port"
                                                output.writeFully("Host: $authority\r\n".encodeToByteArray())

                                                msg.headers.forEach { header ->
                                                    val (key, _) = header.split(": ", limit = 2)
                                                    if (key.equals("Host", ignoreCase = true)) return@forEach
                                                    if (key.equals("Transfer-Encoding", ignoreCase = true)) return@forEach
                                                    // Keep the original Content-Length: the request body is relayed
                                                    // verbatim, so it matches exactly. Without it (and without
                                                    // Transfer-Encoding) HTTP/1.1 treats the request as bodyless and
                                                    // the target server discards the streamed body bytes.
                                                    if (key.equals("Connection", ignoreCase = true)) return@forEach
                                                    output.writeFully("$header\r\n".encodeToByteArray())
                                                }
                                                output.writeFully("Connection: close\r\n".encodeToByteArray())
                                                output.writeFully("\r\n".encodeToByteArray())

                                                if (channel != null) {
                                                    (channel as ByteReadChannel).rawChunks { rawBytes ->
                                                        output.writeFully(rawBytes)
                                                    }
                                                }

                                                val statusLine = try {
                                                    input.readLine() ?: return@launch
                                                } catch (e: Exception) {
                                                    socket.close()
                                                    return@launch
                                                }

                                                val rawHeaders = mutableListOf<String>()
                                                while (true) {
                                                    val line = input.readLine() ?: break
                                                    if (line.isEmpty()) break
                                                    rawHeaders.add(line)
                                                }

                                                val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

                                                val isChunked = rawHeaders.any { it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.substringAfter(":").contains("chunked", ignoreCase = true) }

                                                sendSerialized<ClientMessage>(ClientMessage.HttpResponse(
                                                    requestId = msg.requestId,
                                                    statusCode = statusCode,
                                                    headers = if (isChunked) rawHeaders.filterNot { it.startsWith("Transfer-Encoding:", ignoreCase = true) } else rawHeaders
                                                ))

                                                val bodyBuffer = ByteArray(64 * 1024)

                                                if (isChunked) {
                                                    while (true) {
                                                        val sizeLine = try {
                                                            input.readLine()
                                                        } catch (_: Exception) { null } ?: break
                                                        if (sizeLine.isEmpty()) continue
                                                        val chunkSize = sizeLine.toIntOrNull(16) ?: break
                                                        if (chunkSize == 0) {
                                                            while (true) {
                                                                val trailerLine = try {
                                                                    input.readLine()
                                                                } catch (_: Exception) { null } ?: break
                                                                if (trailerLine.isEmpty()) break
                                                            }
                                                            break
                                                        }
                                                        var remaining = chunkSize
                                                        while (remaining > 0) {
                                                            val toRead = minOf(remaining, bodyBuffer.size)
                                                            val read = try {
                                                                input.readAvailable(bodyBuffer, 0, toRead)
                                                            } catch (_: Exception) { break }
                                                            if (read <= 0) break
                                                            val frameData = ByteArray(16 + read)
                                                            msg.requestId.toByteArray().copyInto(frameData)
                                                            bodyBuffer.copyInto(frameData, 16, 0, read)
                                                            this@serverSession.send(Frame.Binary(true, frameData))
                                                            remaining -= read
                                                        }
                                                    }
                                                } else {
                                                    while (!input.isClosedForRead) {
                                                        val read = try {
                                                            input.readAvailable(bodyBuffer)
                                                        } catch (_: Exception) { break }
                                                        if (read <= 0) break
                                                        val frameData = ByteArray(16 + read)
                                                        msg.requestId.toByteArray().copyInto(frameData)
                                                        bodyBuffer.copyInto(frameData, 16, 0, read)
                                                        this@serverSession.send(Frame.Binary(true, frameData))
                                                    }
                                                }

                                                sendSerialized<ClientMessage>(ClientMessage.HttpEnd(
                                                    requestId = msg.requestId
                                                ))

                                                _requests.update { list ->
                                                    list.map { request ->
                                                        if (request.requestId != msg.requestId) return@map request
                                                        request.copy(
                                                            result = Request.Result.Success(
                                                                finishedAt = Clock.System.now(),
                                                                statusCode = statusCode,
                                                                headers = rawHeaders.map { header ->
                                                                    val (key, value) = header.split(": ", limit = 2)
                                                                    key to value
                                                                },
                                                            )
                                                        )
                                                    }
                                                }

                                                socket.close()
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

                                                this@serverSession.sendSerialized<ClientMessage>(ClientMessage.RequestResolved(
                                                    requestId = msg.requestId,
                                                    service = target.service.name,
                                                ))

//                                                printRequest(
//                                                    method = "WEBSOCKET",
//                                                    projectKey = target.project.id,
//                                                    serviceKey = target.service.name,
//                                                    path = msg.path,
//                                                    targetUrl = target.url,
//                                                )

                                                try {
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
                                                } catch (e: Exception) {
                                                    this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsClose(
                                                        requestId = msg.requestId,
                                                        code = 1011,
                                                        reason = e.message ?: "Failed to connect to local WebSocket service"
                                                    ))
                                                } finally {
                                                    wsProxyState.remove(msg.requestId)
                                                }
                                            }
                                        }
                                        is ServerMessage.WsText -> {
                                            wsProxyState[msg.requestId]?.send(Frame.Text(msg.text))
                                        }
                                        is ServerMessage.WsBinary -> {
                                            wsProxyState[msg.requestId]?.send(Frame.Binary(msg.fin, Base64.decode(msg.body)))
                                        }
                                        is ServerMessage.WsClose -> {
                                            wsProxyState[msg.requestId]?.close(CloseReason(msg.code.toShort(), msg.reason))
                                            wsProxyState.remove(msg.requestId)
                                        }
                                        is ServerMessage.Ping -> {
                                            sendSerialized<ClientMessage>(ClientMessage.Pong(msg.requestId))
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

    fun onSelectPrevious() {
        state.update { state ->
            val requests = requests.value
            val currentSelectedIndex = (state.highlightedRequestId?.let { requests.indexOf(requests.first { it.requestId == state.highlightedRequestId }) } ?: -1).takeIf { it != -1 } ?: (requests.size - 1)
            val targetIndex = (currentSelectedIndex - 1).coerceAtLeast(0)
            val highlightedRequestId = requests.getOrNull(targetIndex)?.requestId
            state.copy(highlightedRequestId = highlightedRequestId)
        }
    }

    fun onSelectNext() {
        state.update { state ->
            val requests = requests.value
            val currentSelectedIndex = (state.highlightedRequestId?.let { requests.indexOf(requests.first { it.requestId == state.highlightedRequestId }) } ?: -1).takeIf { it != -1 } ?: -1
            val targetIndex = (currentSelectedIndex + 1).coerceAtMost(requests.size - 1)
            val highlightedRequestId = requests.getOrNull(targetIndex)?.requestId
            state.copy(highlightedRequestId = highlightedRequestId)
        }
    }

    fun onSelectLatest() {
        state.update { state ->
            state.copy(highlightedRequestId = requests.value.lastOrNull()?.requestId)
        }
    }

    fun onSelectOldest() {
        state.update { state ->
            state.copy(highlightedRequestId = requests.value.firstOrNull()?.requestId)
        }
    }

    fun onShowRequestDetails() {
        state.update { state ->
            state.copy(showRequestDetailsPanel = true)
        }
    }

    fun onHideRequestDetails() {
        state.update { state ->
            state.copy(showRequestDetailsPanel = false)
        }
    }
}

data class TunnelState(
    val connectionState: ConnectionState = ConnectionState.Connecting,
    val highlightedRequestId: Uuid? = null,
    val showRequestDetailsPanel: Boolean = false,
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
    val headers: List<Pair<String, String>>,
    val result: Result?,
) {
    sealed class Result {
        abstract val finishedAt: Instant

        data class Success(
            override val finishedAt: Instant,
            val statusCode: Int,
            val headers: List<Pair<String, String>>,
        ): Result()

        data class Timeout(override val finishedAt: Instant): Result()
        data class ServiceNotRunning(override val finishedAt: Instant): Result()
    }
}