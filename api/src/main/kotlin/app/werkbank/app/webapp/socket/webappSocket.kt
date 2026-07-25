package app.werkbank.app.webapp.socket

import app.werkbank.app.tunnel.RequestId
import app.werkbank.app.tunnel.RequestKind
import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.app.tunnel.TunnelRequestRecord
import app.werkbank.app.tunnel.WsBridge
import app.werkbank.app.tunnel.WsFrameRecord
import app.werkbank.database.TunnelRequest
import app.werkbank.database.TunnelRequestResult
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.util.launchConnectionJob
import com.google.gson.Gson
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import java.util.concurrent.ConcurrentHashMap

private val webAppJson = Json { ignoreUnknownKeys = true }

fun Route.webappSocket() {

    val tunnelManager by inject<TunnelManager>()

    authenticate(AUTH_USER_JWT) {
        webSocket {
            val principal = call.principal<UserPrincipal>()!!

            var activeTunnelJob: Job? = null
            val frameWatchers = ConcurrentHashMap<String, Job>()

            launchConnectionJob(call.application, "webapp-tunnel-updates") {
                tunnelManager.tunnelFlow(principal.user).collect { tunnel ->
                    activeTunnelJob?.cancel()
                    activeTunnelJob = null

                    if (tunnel == null) {
                        sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelInactive)
                        return@collect
                    }

                    activeTunnelJob = launchConnectionJob(call.application, "webapp-tunnel-active") {
                        // StateFlow emits the current ping immediately, so this also signals TunnelActive.
                        launch {
                            tunnel.pingMs.collect { pingMs ->
                                sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelActive(pingMs = pingMs))
                            }
                        }

                        // Observe every request individually. A snapshot StateFlow replays its current
                        // state on subscription, so the history is sent automatically and every later
                        // phase transition (incl. WebSocket frame counters) streams as a RequestUpdate.
                        val observed = mutableSetOf<RequestId>()
                        tunnel.requests.collect { requests ->
                            requests.filter { observed.add(it.requestId) }.forEach { request ->
                                launch {
                                    request.snapshot.collect { sendSerialized<WebAppServerMessage>(it.toRequestUpdate()) }
                                }
                            }
                        }
                    }
                }
            }

            // The detail page subscribes to a specific WebSocket connection's frame timeline via
            // watch/unwatch; frames are streamed only while a client is actually watching.
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = runCatching {
                        webAppJson.decodeFromString<WebAppClientMessage>(frame.readText())
                    }.getOrNull() ?: continue

                    when (message) {
                        is WebAppClientMessage.Watch -> {
                            frameWatchers.remove(message.requestId)?.cancel()
                            val bridge = tunnelManager.getTunnel(principal.user)
                                ?.requests?.value
                                ?.firstOrNull { it.requestId.toString() == message.requestId } as? WsBridge
                                ?: continue
                            frameWatchers[message.requestId] = launchConnectionJob(call.application, "webapp-ws-frames") {
                                streamFrames(message.requestId, bridge)
                            }
                        }

                        is WebAppClientMessage.Unwatch -> frameWatchers.remove(message.requestId)?.cancel()
                    }
                }
            } finally {
                activeTunnelJob?.cancel()
                frameWatchers.values.forEach { it.cancel() }
            }
        }
    }
}

/**
 * Streams a WebSocket connection's frame timeline: subscribe to live frames first, then replay the
 * snapshot, then drain the live buffer skipping already-replayed sequences. Gap-free and dup-free.
 */
private suspend fun DefaultWebSocketServerSession.streamFrames(requestId: String, bridge: WsBridge) {
    coroutineScope {
        val buffer = Channel<WsFrameRecord>(Channel.UNLIMITED)
        launch { bridge.frameEvents.collect { buffer.send(it) } }

        var nextSequence = 0
        bridge.framesSnapshot().forEach {
            sendSerialized<WebAppServerMessage>(it.toWsFrame(requestId))
            nextSequence = it.sequence + 1
        }
        for (frame in buffer) {
            if (frame.sequence >= nextSequence) {
                sendSerialized<WebAppServerMessage>(frame.toWsFrame(requestId))
                nextSequence = frame.sequence + 1
            }
        }
    }
}

private val gson = Gson()
suspend fun DefaultWebSocketServerSession.sendJson(data: Map<String, Any?>) {
    this.send(gson.toJson(data))
}

private fun TunnelRequestRecord.toRequestUpdate(): WebAppServerMessage.RequestUpdate =
    WebAppServerMessage.RequestUpdate(
        requestId = requestId.toString(),
        kind = when (kind) {
            RequestKind.HTTP -> "http"
            RequestKind.WEBSOCKET -> "websocket"
        },
        method = method,
        uri = uri,
        target = WebAppServerMessage.RequestTarget(
            projectId = projectId,
            projectName = projectName,
            serviceName = serviceName,
        ),
        statusCode = statusCode,
        error = error,
        startedAt = startedAt,
        sentToTunnelAt = sentToTunnelAt,
        responseStartedAt = responseStartedAt,
        completedAt = completedAt,
        wsFramesSent = wsFramesSent,
        wsFramesReceived = wsFramesReceived,
    )

private fun WsFrameRecord.toWsFrame(requestId: String): WebAppServerMessage.WsFrame =
    WebAppServerMessage.WsFrame(
        requestId = requestId,
        sequence = sequence,
        direction = direction.name.lowercase(),
        opcode = opcode.name.lowercase(),
        text = text,
        binaryBase64 = binaryBase64,
        size = size,
        timestamp = timestamp,
        closeCode = closeCode,
        closeReason = closeReason,
    )

@Serializable
sealed class WebAppClientMessage {
    @Serializable
    @SerialName("watch")
    data class Watch(
        @SerialName("request_id") val requestId: String,
    ): WebAppClientMessage()

    @Serializable
    @SerialName("unwatch")
    data class Unwatch(
        @SerialName("request_id") val requestId: String,
    ): WebAppClientMessage()
}

@Serializable
sealed class WebAppServerMessage {
    @Serializable
    @SerialName("tunnel.active")
    data class TunnelActive(
        @SerialName("ping_ms") val pingMs: Long? = null,
    ): WebAppServerMessage()

    @Serializable
    @SerialName("tunnel.inactive")
    data object TunnelInactive: WebAppServerMessage()

    @Serializable
    @SerialName("request.update")
    data class RequestUpdate(
        @SerialName("request_id") val requestId: String,
        @SerialName("kind") val kind: String,
        @SerialName("method") val method: String,
        @SerialName("uri") val uri: String,
        @SerialName("target") val target: RequestTarget?,
        @SerialName("status_code") val statusCode: Int?,
        @SerialName("error") val error: String?,
        @SerialName("started_at") val startedAt: Long,
        @SerialName("sent_to_tunnel_at") val sentToTunnelAt: Long?,
        @SerialName("response_started_at") val responseStartedAt: Long?,
        @SerialName("completed_at") val completedAt: Long?,
        @SerialName("ws_frames_sent") val wsFramesSent: Int = 0,
        @SerialName("ws_frames_received") val wsFramesReceived: Int = 0,
    ): WebAppServerMessage() {
        companion object {
            fun from(request: TunnelRequest): RequestUpdate = RequestUpdate(
                requestId = request.id.value.toString(),
                kind = request.kind ?: "http",
                method = request.method,
                uri = request.uri,
                target = RequestTarget(
                    projectId = request.project.id.value.toString(),
                    projectName = request.project.name,
                    serviceName = request.service?.serviceKey,
                ),
                statusCode = (request.result as? TunnelRequestResult.Success)?.statusCode,
                error = (request.result as? TunnelRequestResult.Failure)?.error,
                startedAt = request.startedAt.epochSeconds,
                sentToTunnelAt = request.startedAt.epochSeconds,
                responseStartedAt = request.responseReadyAt?.epochSeconds,
                completedAt = request.responseReadyAt?.epochSeconds,
                wsFramesSent = request.wsFramesSent,
                wsFramesReceived = request.wsFramesReceived,
            )
        }
    }

    @Serializable
    @SerialName("ws.frame")
    data class WsFrame(
        @SerialName("request_id") val requestId: String,
        @SerialName("sequence") val sequence: Int,
        @SerialName("direction") val direction: String,
        @SerialName("opcode") val opcode: String,
        @SerialName("text") val text: String?,
        @SerialName("binary_base64") val binaryBase64: String?,
        @SerialName("size") val size: Int,
        @SerialName("timestamp") val timestamp: Long,
        @SerialName("close_code") val closeCode: Int?,
        @SerialName("close_reason") val closeReason: String?,
    ): WebAppServerMessage()

    @Serializable
    data class RequestTarget(
        @SerialName("project_id") val projectId: String,
        @SerialName("project_name") val projectName: String,
        @SerialName("service_name") val serviceName: String?,
    )
}
