package commands.tunnel

import app.config.MainConfig
import app.process.ProcessInfo
import app.process.currentProcessId
import app.process.isProcessRunning
import app.process.processInfo
import app.process.stopProcess
import app.storage.TunnelPidFile
import app.werkbank.shared.tunnel.*
import http.httpClientBase
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.network.selector.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
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

        /** How long a tunnel gets to shut down after the dialog asked it to, before reconnecting anyway. */
        private val STOP_TUNNEL_TIMEOUT = 5.seconds

        /** How many requests the TUI keeps; older entries are dropped from the front. */
        private const val MAX_TRACKED_REQUESTS = 500

        // WebSocket handshake control headers the ktor client manages itself; forwarding the browser's
        // copies would conflict. Everything else (incl. Sec-WebSocket-Protocol, e.g. Vite's "vite-hmr")
        // is passed through to the upstream so the dev server negotiates the handshake correctly.
        private val NON_FORWARDED_WS_HEADERS = setOf(
            "host",
            "connection",
            "upgrade",
            "sec-websocket-key",
            "sec-websocket-version",
            "sec-websocket-extensions",
            "sec-websocket-accept",
            "content-length",
        )

        /**
         * The scheme the public entrypoint was reached on. The cloud proxy always terminates
         * TLS; only the hop from this client to the local service is plain HTTP.
         */
        private const val PUBLIC_SCHEME = "https"

        /** Reads a header out of the raw `"Name: value"` lines the server forwards. */
        private fun List<String>.headerValue(name: String): String? = firstNotNullOfOrNull { header ->
            val separator = header.indexOf(": ")
            if (separator <= 0) return@firstNotNullOfOrNull null
            if (!header.substring(0, separator).equals(name, ignoreCase = true)) return@firstNotNullOfOrNull null
            header.substring(separator + 2)
        }
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
    private val connectionPool = LocalConnectionPool(selectorManager)

    private val viewModelScope = CoroutineScope(Dispatchers.Default)

    /** Retry presses from the "tunnel already running" dialog; see the connect loop below. */
    private val retryRequests = Channel<Unit>(Channel.CONFLATED)

    private val exit = CompletableDeferred<Unit>()

    /** Completes once the user chose to quit; the command tears the UI down when it does. */
    suspend fun awaitExit() = exit.await()

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
                // Partially received binary messages from the browser, keyed by request id; see the
                // Frame.Binary branch below.
                val wsBinaryFragments = mutableMapOf<Uuid, ByteArray>()

                // Set when the server refused this tunnel because another one is already connected;
                // the socket then closes right away, without any exception to catch below.
                var rejected: String? = null

                /** Whether this connection made it past the server's one-tunnel-per-account check. */
                var owner = false

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
                                // Every ping updates the state and re-renders the TUI, and its frames
                                // compete with request traffic on the tunnel socket — keep it rare.
                                delay(2.seconds)
                            }
                        }
                        state.update { it.copy(connectionState = TunnelState.ConnectionState.Connected(currentPing = null)) }

                        for (message in incoming) {
                            if (!owner) {
                                // A refused tunnel is closed before the server sends anything, so the
                                // first frame proves that this process, and no other, owns the slot.
                                owner = true
                                TunnelPidFile.write(currentProcessId())
                            }
                            when (message) {
                                is Frame.Binary -> {
                                    val bytes = message.readBytes()
                                    if (bytes.size < TunnelFrame.HEADER_SIZE) continue
                                    val id = TunnelFrame.requestId(bytes)
                                    val payload = TunnelFrame.payload(bytes)
                                    if (TunnelFrame.isWebSocket(bytes)) {
                                        // Buffer fragments until the FIN arrives and forward the message
                                        // as one frame: ktor writes every Frame.Binary with the BINARY
                                        // opcode, never a continuation, so relaying fragments verbatim
                                        // would look like a new message starting mid-message and the
                                        // local service would fail the connection.
                                        if (!TunnelFrame.isFin(bytes)) {
                                            wsBinaryFragments[id] = (wsBinaryFragments[id] ?: ByteArray(0)) + payload
                                        } else {
                                            val message = wsBinaryFragments.remove(id)?.plus(payload) ?: payload
                                            wsProxyState[id]?.send(Frame.Binary(true, message))
                                            updateWs(id) { it.copy(framesSent = it.framesSent + 1) }
                                        }
                                    } else {
                                        requestBodies[id]?.writeFully(payload)
                                        requestBodies[id]?.flush()
                                    }
                                }
                                is Frame.Text -> {
                                    when (val msg = json.decodeFromString<ServerMessage>(message.readText())) {
                                        is ServerMessage.HttpRequest -> {
                                            if (msg.method != "GET") {
                                                requestBodies[msg.requestId] = ByteChannel(autoFlush = true)
                                            }

                                            launch {
                                                val checkpoints = RequestCheckpoints()
                                                checkpoints.mark("Received ${msg.method} request for ${msg.path}")
                                                // Set once the status line has been forwarded: after that the
                                                // browser already owns the response, so a later failure must
                                                // just end the body stream, never trigger the error page.
                                                var responseSent = false
                                                try {
                                                    val channel = requestBodies[msg.requestId]
                                                    val target = when (val resolution = tunnelRequestResolver.getTarget(
                                                        projectKey = msg.project,
                                                        serviceKey = msg.service,
                                                        path = msg.path,
                                                        isWebsocket = false,
                                                    )) {
                                                        is TunnelRequestResolver.Resolution.Resolved -> resolution.target
                                                        is TunnelRequestResolver.Resolution.Failed -> {
                                                            checkpoints.mark("Could not resolve target: ${resolution.reason}")
                                                            sendSerialized<ClientMessage>(ClientMessage.UnexpectedError(
                                                                requestId = msg.requestId,
                                                                message = resolution.reason,
                                                                checkpoints = checkpoints.toList(),
                                                            ))
                                                            return@launch
                                                        }
                                                    }
                                                    checkpoints.mark("Resolved target ${target.url}")

                                                    sendSerialized<ClientMessage>(ClientMessage.RequestResolved(
                                                        requestId = msg.requestId,
                                                        service = target.service.name,
                                                    ))

                                                    // Capped so a long tunnel session can't grow the list unboundedly:
                                                    // every update copies it, and each finished request maps over it.
                                                    _requests.update { it.takeLast(MAX_TRACKED_REQUESTS - 1) + Request(
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
                                                    val hasRequestBody = channel != null

                                                    // The whole request head is built once and written as a single
                                                    // buffer: line-by-line writes on an autoflushing channel cost one
                                                    // syscall (and one TCP segment) per header.
                                                    //
                                                    // We dial 127.0.0.1, so without the client's Host and the X-Forwarded-*
                                                    // headers the service only ever sees the loopback authority over plain
                                                    // HTTP: absolute URLs, redirects, signed-URL checks and host allow-lists
                                                    // (Vite) all break. traefik does the same on the local route.
                                                    val authority = if (port == targetUrl.protocol.defaultPort) host else "$host:$port"
                                                    val clientHost = msg.headers.headerValue("Host")
                                                    val head = buildString {
                                                        append(msg.method).append(' ').append(path).append(" HTTP/1.1\r\n")
                                                        append("Host: ").append(clientHost ?: authority).append("\r\n")
                                                        if (clientHost != null && msg.headers.headerValue("X-Forwarded-Host") == null) {
                                                            append("X-Forwarded-Host: ").append(clientHost).append("\r\n")
                                                        }
                                                        if (msg.headers.headerValue("X-Forwarded-Proto") == null) {
                                                            append("X-Forwarded-Proto: ").append(PUBLIC_SCHEME).append("\r\n")
                                                        }
                                                        msg.headers.forEach { header ->
                                                            val (key, _) = header.split(": ", limit = 2)
                                                            if (key.equals("Host", ignoreCase = true)) return@forEach
                                                            if (key.equals("Transfer-Encoding", ignoreCase = true)) return@forEach
                                                            // Keep the original Content-Length: the request body is relayed
                                                            // verbatim, so it matches exactly. Without it (and without
                                                            // Transfer-Encoding) HTTP/1.1 treats the request as bodyless and
                                                            // the target server discards the streamed body bytes.
                                                            if (key.equals("Connection", ignoreCase = true)) return@forEach
                                                            append(header).append("\r\n")
                                                        }
                                                        // Bodyless requests ride pooled keep-alive connections; a request
                                                        // with a body uses a one-shot connection because its streamed body
                                                        // is consumed and could not be replayed on a stale-reuse retry.
                                                        if (hasRequestBody) append("Connection: close\r\n")
                                                        append("\r\n")
                                                    }.encodeToByteArray()

                                                    suspend fun sendRequest(lease: LocalConnectionPool.Lease): String? {
                                                        lease.output.writeFully(head)
                                                        lease.output.flush()
                                                        if (channel != null) {
                                                            (channel as ByteReadChannel).rawChunks { rawBytes ->
                                                                lease.output.writeFully(rawBytes)
                                                                lease.output.flush()
                                                            }
                                                        }
                                                        checkpoints.mark("Request forwarded, awaiting response")
                                                        return lease.input.readLine()
                                                    }

                                                    checkpoints.mark("Connecting to $host:$port")
                                                    var lease = try {
                                                        connectionPool.acquire(host, port, isHttps, currentCoroutineContext(), poolable = !hasRequestBody)
                                                    } catch (e: Exception) {
                                                        val isTimeout = e.message?.contains("timed out", ignoreCase = true) == true
                                                        if (isTimeout) {
                                                            checkpoints.mark("Connection to $host:$port timed out")
                                                            _requests.update { list -> list.map { if (it.requestId == msg.requestId) it.copy(result = Request.Result.Timeout(Clock.System.now())) else it } }
                                                            sendSerialized<ClientMessage>(ClientMessage.Timeout(requestId = msg.requestId))
                                                        } else {
                                                            checkpoints.mark("Failed to connect to $host:$port: ${e.message}")
                                                            _requests.update { list -> list.map { if (it.requestId == msg.requestId) it.copy(result = Request.Result.ServiceNotRunning(Clock.System.now())) else it } }
                                                            sendSerialized<ClientMessage>(ClientMessage.ServerNotRuning(msg.requestId))
                                                        }
                                                        return@launch
                                                    }
                                                    checkpoints.mark(if (lease.reused) "Reusing connection to $host:$port" else "Connected to $host:$port")

                                                    // A pooled connection may have been closed by the server while it sat
                                                    // idle, surfacing as a write error or an immediate EOF. Retried once on
                                                    // a fresh connection — safe because pooled leases never carry a body.
                                                    var statusLine = if (lease.reused) {
                                                        try { sendRequest(lease) } catch (_: Exception) { null }
                                                    } else {
                                                        try {
                                                            sendRequest(lease)
                                                        } catch (e: Exception) {
                                                            checkpoints.mark("Error while reading response status line: ${e.message}")
                                                            lease.close()
                                                            sendSerialized<ClientMessage>(ClientMessage.UnexpectedError(
                                                                requestId = msg.requestId,
                                                                message = "Error while reading the response from ${target.service.name}: ${e.message}",
                                                                checkpoints = checkpoints.toList(),
                                                            ))
                                                            return@launch
                                                        }
                                                    }
                                                    if (statusLine == null && lease.reused) {
                                                        checkpoints.mark("Pooled connection was stale, retrying on a fresh one")
                                                        lease.close()
                                                        lease = try {
                                                            connectionPool.acquire(host, port, isHttps, currentCoroutineContext(), poolable = true, forceFresh = true)
                                                        } catch (e: Exception) {
                                                            checkpoints.mark("Failed to connect to $host:$port: ${e.message}")
                                                            _requests.update { list -> list.map { if (it.requestId == msg.requestId) it.copy(result = Request.Result.ServiceNotRunning(Clock.System.now())) else it } }
                                                            sendSerialized<ClientMessage>(ClientMessage.ServerNotRuning(msg.requestId))
                                                            return@launch
                                                        }
                                                        statusLine = try {
                                                            sendRequest(lease)
                                                        } catch (e: Exception) {
                                                            checkpoints.mark("Error while reading response status line: ${e.message}")
                                                            lease.close()
                                                            sendSerialized<ClientMessage>(ClientMessage.UnexpectedError(
                                                                requestId = msg.requestId,
                                                                message = "Error while reading the response from ${target.service.name}: ${e.message}",
                                                                checkpoints = checkpoints.toList(),
                                                            ))
                                                            return@launch
                                                        }
                                                    }
                                                    if (statusLine == null) {
                                                        checkpoints.mark("Local service closed the connection before responding")
                                                        lease.close()
                                                        sendSerialized<ClientMessage>(ClientMessage.UnexpectedError(
                                                            requestId = msg.requestId,
                                                            message = "The service ${target.service.name} closed the connection before sending a response",
                                                            checkpoints = checkpoints.toList(),
                                                        ))
                                                        return@launch
                                                    }

                                                    val input = lease.input
                                                    val rawHeaders = mutableListOf<String>()
                                                    while (true) {
                                                        val line = input.readLine() ?: break
                                                        if (line.isEmpty()) break
                                                        rawHeaders.add(line)
                                                    }

                                                    val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                                                    checkpoints.mark("Received response status $statusCode")

                                                    val isChunked = rawHeaders.any { it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.substringAfter(":").contains("chunked", ignoreCase = true) }
                                                    val contentLength = rawHeaders.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                                        ?.substringAfter(":")?.trim()?.toLongOrNull()
                                                    val isBodyless = msg.method.equals("HEAD", ignoreCase = true) ||
                                                        statusCode == 204 || statusCode == 304 || statusCode < 200

                                                    sendSerialized<ClientMessage>(ClientMessage.HttpResponse(
                                                        requestId = msg.requestId,
                                                        statusCode = statusCode,
                                                        headers = if (isChunked) rawHeaders.filterNot { it.startsWith("Transfer-Encoding:", ignoreCase = true) } else rawHeaders
                                                    ))
                                                    responseSent = true

                                                    val bodyBuffer = ByteArray(64 * 1024)

                                                    // Whether the body ended exactly where its framing said it would; only
                                                    // then is the connection in a known state and safe to pool.
                                                    var framedCleanly = false

                                                    if (isBodyless) {
                                                        framedCleanly = true
                                                    } else if (isChunked) {
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
                                                                    if (trailerLine.isEmpty()) {
                                                                        framedCleanly = true
                                                                        break
                                                                    }
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
                                                                // HTTP body chunk: flags = 0. Must go through TunnelFrame so
                                                                // the [16 id][1 flags][payload] layout matches what the API
                                                                // decodes — a hand-rolled 16-byte header shifts the payload
                                                                // and makes the API read the first body byte as the flags byte.
                                                                this@serverSession.send(Frame.Binary(true, TunnelFrame.encode(msg.requestId, 0, bodyBuffer, read)))
                                                                remaining -= read
                                                            }
                                                        }
                                                    } else if (contentLength != null) {
                                                        var remaining = contentLength
                                                        while (remaining > 0) {
                                                            val toRead = minOf(remaining, bodyBuffer.size.toLong()).toInt()
                                                            val read = try {
                                                                input.readAvailable(bodyBuffer, 0, toRead)
                                                            } catch (_: Exception) { break }
                                                            if (read <= 0) break
                                                            this@serverSession.send(Frame.Binary(true, TunnelFrame.encode(msg.requestId, 0, bodyBuffer, read)))
                                                            remaining -= read
                                                        }
                                                        framedCleanly = remaining == 0L
                                                    } else {
                                                        // Neither chunked nor Content-Length: the body is delimited by the
                                                        // connection closing, which also rules out reuse.
                                                        while (!input.isClosedForRead) {
                                                            val read = try {
                                                                input.readAvailable(bodyBuffer)
                                                            } catch (_: Exception) { break }
                                                            if (read <= 0) break
                                                            this@serverSession.send(Frame.Binary(true, TunnelFrame.encode(msg.requestId, 0, bodyBuffer, read)))
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

                                                    val upstreamClosing = rawHeaders.any { it.startsWith("Connection:", ignoreCase = true) && it.substringAfter(":").contains("close", ignoreCase = true) }
                                                    if (lease.poolable && framedCleanly && !upstreamClosing) {
                                                        connectionPool.release(lease)
                                                    } else {
                                                        lease.close()
                                                    }
                                                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    checkpoints.mark("Unexpected error: ${e.message}")
                                                    // Once the response headers are out the browser owns the
                                                    // stream; the best we can do is end it. Only before that
                                                    // can the server still render the unexpected-error page.
                                                    try {
                                                        if (responseSent) {
                                                            sendSerialized<ClientMessage>(ClientMessage.HttpEnd(requestId = msg.requestId))
                                                        } else {
                                                            sendSerialized<ClientMessage>(ClientMessage.UnexpectedError(
                                                                requestId = msg.requestId,
                                                                message = e.message ?: "Unexpected error while handling the request",
                                                                checkpoints = checkpoints.toList(),
                                                            ))
                                                        }
                                                    } catch (_: Exception) {
                                                        // The tunnel itself is gone; nothing more we can send.
                                                    }
                                                }
                                            }
                                        }
                                        is ServerMessage.HttpEnd -> {
                                            requestBodies[msg.requestId]?.flushAndClose()
                                            requestBodies.remove(msg.requestId)
                                        }
                                        is ServerMessage.WsOpen -> {
                                            launch wsRelay@{
                                                val target = when (val resolution = tunnelRequestResolver.getTarget(
                                                    projectKey = msg.project,
                                                    serviceKey = msg.service,
                                                    path = msg.path,
                                                    isWebsocket = true,
                                                )) {
                                                    is TunnelRequestResolver.Resolution.Resolved -> resolution.target
                                                    is TunnelRequestResolver.Resolution.Failed -> {
                                                        // Fail the handshake fast instead of letting the server
                                                        // wait out its open-timeout on a request that can't resolve.
                                                        this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsClose(
                                                            requestId = msg.requestId,
                                                            code = 1011,
                                                            reason = resolution.reason,
                                                        ))
                                                        return@wsRelay
                                                    }
                                                }

                                                this@serverSession.sendSerialized<ClientMessage>(ClientMessage.RequestResolved(
                                                    requestId = msg.requestId,
                                                    service = target.service.name,
                                                ))

                                                _requests.update { it.takeLast(MAX_TRACKED_REQUESTS - 1) + Request(
                                                    requestId = msg.requestId,
                                                    method = "WS/GET",
                                                    project = target.project.id,
                                                    service = target.service.name,
                                                    path = msg.path,
                                                    targetUrl = target.url,
                                                    startedAt = Clock.System.now(),
                                                    headers = msg.headers.map { header ->
                                                        val (key, value) = header.split(": ")
                                                        key to value
                                                    },
                                                    result = null,
                                                    ws = Request.WsState(),
                                                ) }

                                                try {
                                                    client.webSocket(
                                                        urlString = target.url,
                                                        request = {
                                                            msg.headers.forEach { header ->
                                                                val separator = header.indexOf(": ")
                                                                if (separator <= 0) return@forEach
                                                                val name = header.substring(0, separator)
                                                                val value = header.substring(separator + 2)
                                                                if (name.lowercase() !in NON_FORWARDED_WS_HEADERS) {
                                                                    headers.append(name, value)
                                                                }
                                                            }
                                                            // The ktor client owns Host for the handshake, so the public
                                                            // origin can only travel in X-Forwarded-* here.
                                                            msg.headers.headerValue("Host")?.let { clientHost ->
                                                                if (msg.headers.headerValue("X-Forwarded-Host") == null) {
                                                                    headers.append("X-Forwarded-Host", clientHost)
                                                                }
                                                            }
                                                            if (msg.headers.headerValue("X-Forwarded-Proto") == null) {
                                                                headers.append("X-Forwarded-Proto", PUBLIC_SCHEME)
                                                            }
                                                        }
                                                    ) {
                                                        wsProxyState[msg.requestId] = this
                                                        this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsOpened(
                                                            requestId = msg.requestId,
                                                            headers = call.response.headers.entries().flatMap { (name, values) ->
                                                                values.map { "$name: $it" }
                                                            },
                                                        ))

                                                        for (frame in incoming) {
                                                            when (frame) {
                                                                is Frame.Text -> {
                                                                    updateWs(msg.requestId) { it.copy(framesReceived = it.framesReceived + 1) }
                                                                    this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsText(
                                                                        requestId = msg.requestId,
                                                                        text = frame.readText()
                                                                    ))
                                                                }
                                                                is Frame.Binary -> {
                                                                    updateWs(msg.requestId) { it.copy(framesReceived = it.framesReceived + 1) }
                                                                    this@serverSession.send(Frame.Binary(true, TunnelFrame.encode(
                                                                        msg.requestId,
                                                                        TunnelFrame.webSocketFlags(frame.fin),
                                                                        frame.readBytes(),
                                                                    )))
                                                                }
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
                                                    // Our own shutdown is not a failure of this connection.
                                                    if (e is kotlin.coroutines.cancellation.CancellationException && !this@wsRelay.isActive) throw e
                                                    // The Darwin engine ends a finished WebSocket task by cancelling
                                                    // the session job instead of delivering a close frame, so a
                                                    // cancellation with this coroutine still alive means the local
                                                    // service hung up — a normal close, not an internal error.
                                                    val upstreamClosed = e is kotlin.coroutines.cancellation.CancellationException
                                                    this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsClose(
                                                        requestId = msg.requestId,
                                                        code = if (upstreamClosed) 1001 else 1011,
                                                        reason = when {
                                                            upstreamClosed -> "The service ${target.service.name} closed the WebSocket"
                                                            else -> e.message ?: "Failed to connect to local WebSocket service"
                                                        },
                                                    ))
                                                } finally {
                                                    updateWs(msg.requestId) { it.copy(closed = true) }
                                                    wsProxyState.remove(msg.requestId)
                                                    wsBinaryFragments.remove(msg.requestId)
                                                }
                                            }
                                        }
                                        is ServerMessage.WsText -> {
                                            wsProxyState[msg.requestId]?.send(Frame.Text(msg.text))
                                            updateWs(msg.requestId) { it.copy(framesSent = it.framesSent + 1) }
                                        }
                                        is ServerMessage.WsClose -> {
                                            wsProxyState[msg.requestId]?.close(CloseReason(msg.code.toShort(), msg.reason))
                                            updateWs(msg.requestId) { it.copy(closed = true) }
                                            wsProxyState.remove(msg.requestId)
                                            wsBinaryFragments.remove(msg.requestId)
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

                        rejected = closeReason.await()
                            ?.takeIf { it.knownReason == CloseReason.Codes.VIOLATED_POLICY }
                            ?.message
                    }
                } catch (e: Exception) {
                    // Released here as well as below because the retry delay may never return.
                    if (owner) TunnelPidFile.clear(currentProcessId())
                    state.update { it.copy(connectionState = TunnelState.ConnectionState.Retrying(Clock.System.now() + TUNNEL_RECONNECT_DELAY, e)) }
                    delay(TUNNEL_RECONNECT_DELAY)
                }
                // The slot is gone with the socket, so another tunnel may take the pid file over.
                if (owner) TunnelPidFile.clear(currentProcessId())

                // A rejection ends the socket cleanly, so reconnecting right away would only hammer
                // the server. Ask the user instead: only they know whether the other tunnel should
                // keep the slot.
                val rejection = rejected
                if (rejection != null) {
                    // Drop anything sent while no dialog was up, so a stale request cannot skip this one.
                    do { val stale = retryRequests.tryReceive() } while (stale.isSuccess)
                    state.update {
                        it.copy(connectionState = TunnelState.ConnectionState.Rejected(rejection, localTunnel()))
                    }
                    retryRequests.receive()
                }
            }
        }
    }

    private fun updateWs(requestId: Uuid, transform: (Request.WsState) -> Request.WsState) {
        _requests.update { list ->
            list.map { request ->
                if (request.requestId == requestId && request.ws != null) {
                    request.copy(ws = transform(request.ws))
                } else {
                    request
                }
            }
        }
    }

    fun onCancel() {
        viewModelScope.cancel()
    }

    /**
     * The tunnel process of this machine that is holding the account slot, if there is one.
     *
     * A pid file left behind by a crashed tunnel is ignored, and so is a pid that has meanwhile been
     * reused by an unrelated program — otherwise the dialog would offer to kill a stranger.
     */
    private fun localTunnel(): ProcessInfo? {
        val pid = TunnelPidFile.read()?.takeIf { it != currentProcessId() && isProcessRunning(it) } ?: return null
        val info = processInfo(pid) ?: return null
        val own = processInfo(currentProcessId())?.executableName
        if (own != null && info.executableName != own) return null
        return info
    }

    /** Reconnect after the server refused the tunnel because another one holds the account slot. */
    fun onRetryRejectedTunnel() {
        retryRequests.trySend(Unit)
    }

    /** Shut the local tunnel from the dialog down and reconnect as soon as it released the slot. */
    fun onStopLocalTunnelAndRetry() {
        val other = (state.value.connectionState as? TunnelState.ConnectionState.Rejected)?.localTunnel ?: return
        viewModelScope.launch {
            stopProcess(other.pid)
            // The server frees the slot the moment that process' socket dies, which is why this waits
            // for the process itself rather than reconnecting straight away.
            withTimeoutOrNull(STOP_TUNNEL_TIMEOUT) {
                while (isProcessRunning(other.pid)) delay(100.milliseconds)
            }
            // A tunnel killed by a signal never gets to clean up after itself.
            if (!isProcessRunning(other.pid)) TunnelPidFile.clear(other.pid)
            retryRequests.trySend(Unit)
        }
    }

    /** Give up on a refused tunnel and quit. */
    fun onExit() {
        exit.complete(Unit)
        onCancel()
    }

    fun onSelectPrevious() {
        state.update { state ->
            val requests = requests.value
            val currentSelectedIndex = requests.indexOfFirst { it.requestId == state.highlightedRequestId }.takeIf { it != -1 } ?: (requests.size - 1)
            val targetIndex = (currentSelectedIndex - 1).coerceAtLeast(0)
            val highlightedRequestId = requests.getOrNull(targetIndex)?.requestId
            state.copy(highlightedRequestId = highlightedRequestId)
        }
    }

    fun onSelectNext() {
        state.update { state ->
            val requests = requests.value
            val currentSelectedIndex = requests.indexOfFirst { it.requestId == state.highlightedRequestId }
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

        /**
         * The server refused the tunnel because another one is already connected for this account.
         * [localTunnel] is set when that other tunnel turns out to be a process on this machine.
         */
        data class Rejected(val reason: String, val localTunnel: ProcessInfo?): ConnectionState()
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
    val ws: WsState? = null,
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

    /**
     * State of a proxied WebSocket connection. Direction mirrors the cloud tunnel: frames going from
     * the browser to the local dev service are counted as [framesSent], frames coming back from the
     * dev service to the browser as [framesReceived].
     */
    data class WsState(
        val framesSent: Int = 0,
        val framesReceived: Int = 0,
        val closed: Boolean = false,
    )
}