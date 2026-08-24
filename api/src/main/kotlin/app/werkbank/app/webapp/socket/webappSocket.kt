package app.werkbank.app.webapp.socket

import app.werkbank.app.tunnel.*
import app.werkbank.app.webapp.requests.REQUEST_PAGE_SIZE
import app.werkbank.app.webapp.requests.requestHistory
import app.werkbank.app.webapp.requests.requestsByIds
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Project
import app.werkbank.database.TunnelRequest
import app.werkbank.database.TunnelRequestResult
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.util.launchConnectionJob
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val webAppJson = Json { ignoreUnknownKeys = true }

fun Route.webappSocket() {

    val tunnelManager by inject<TunnelManager>()
    val db by inject<DatabaseManager>()

    authenticate(AUTH_USER_JWT) {
        webSocket {
            val principal = call.principal<UserPrincipal>()!!

            // A live request only carries the project id and the project key the tunnel addresses,
            // so key and display name are read from the database, once per project and connection.
            val projects = ConcurrentHashMap<String, ProjectNames>()

            suspend fun projectNames(projectId: String, fallbackKey: String): ProjectNames {
                projects[projectId]?.let { return it }

                val id = parseProjectId(projectId)
                val names = id?.let { db.query { Project.findById(it)?.let { ProjectNames(it.projectKey, it.name) } } }
                    // A deleted project has no names left, the key the tunnel used is the best there is.
                    ?: ProjectNames(fallbackKey, fallbackKey)

                projects[projectId] = names
                return names
            }

            var activeTunnelJob: Job? = null
            val frameWatchers = ConcurrentHashMap<String, Job>()

            // Every coroutine here names the scope it belongs to. The session, the connection job and
            // the per-tunnel job are all CoroutineScopes in scope at once, and picking one up
            // implicitly from inside a `collect` would be a coin flip over which one outlives the work.
            val session = this

            session.launchConnectionJob(call.application, "webapp-tunnel-updates") {
                val connection = this

                tunnelManager.tunnelFlow(principal.user).collect { tunnel ->
                    activeTunnelJob?.cancel()
                    activeTunnelJob = null

                    if (tunnel == null) {
                        sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelInactive)
                        return@collect
                    }

                    activeTunnelJob = connection.launchConnectionJob(call.application, "webapp-tunnel-active") {
                        val active = this

                        // StateFlow emits the current ping immediately, so this also signals TunnelActive.
                        active.launch {
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
                                active.launch {
                                    request.snapshot.collect { record ->
                                        sendSerialized<WebAppServerMessage>(
                                            record.toRequestUpdate(projectNames(record.projectId, record.projectName))
                                        )
                                    }
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
                            frameWatchers[message.requestId] =
                                session.launchConnectionJob(call.application, "webapp-ws-frames") {
                                    streamFrames(message.requestId, bridge)
                                }
                        }

                        is WebAppClientMessage.Unwatch -> frameWatchers.remove(message.requestId)?.cancel()

                        is WebAppClientMessage.History -> {
                            val page = db.requestHistory(
                                user = principal.user,
                                before = message.before?.let { Instant.fromEpochMilliseconds(it) },
                                limit = message.limit,
                            )

                            sendSerialized<WebAppServerMessage>(
                                WebAppServerMessage.RequestHistory(
                                    requests = page,
                                    before = message.before,
                                    complete = page.size < message.limit,
                                )
                            )
                        }

                        is WebAppClientMessage.Sync -> {
                            val ids = message.requestIds.distinct().take(MAX_SYNC_IDS)

                            // A request the client cached while it was still running may since have
                            // finished (and be in the database), still be running (and only exist in
                            // the tunnel), or never have been persisted at all. The live tunnel is
                            // asked first so a still-running request is not reported as gone.
                            val live = tunnelManager.getTunnel(principal.user)?.requests?.value
                                ?.associateBy { it.requestId.toString() }
                                .orEmpty()

                            val fromTunnel = ids.mapNotNull { live[it]?.snapshot?.value }
                                .map { it.toRequestUpdate(projectNames(it.projectId, it.projectName)) }

                            val fromDatabase = db.requestsByIds(
                                user = principal.user,
                                ids = ids.filter { it !in live }.mapNotNull { parseRequestId(it) },
                            )

                            val known = fromTunnel + fromDatabase
                            val knownIds = known.mapTo(mutableSetOf()) { it.requestId }

                            sendSerialized<WebAppServerMessage>(
                                WebAppServerMessage.RequestSync(
                                    requests = known,
                                    missing = ids.filter { it !in knownIds },
                                )
                            )
                        }
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
        val frames = this
        val buffer = Channel<WsFrameRecord>(Channel.UNLIMITED)
        // The session is a CoroutineScope too; this collector must die with the watch, not with it.
        frames.launch { bridge.frameEvents.collect { buffer.send(it) } }

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

/** Key and display name of a project, as stored in the database. */
private data class ProjectNames(val key: String, val name: String)

/** Upper bound on a single sync, so a broken client cannot ask for an unbounded query. */
private const val MAX_SYNC_IDS = 500

private fun parseRequestId(requestId: String): Uuid? = runCatching { Uuid.parse(requestId) }.getOrNull()

/** Live records carry the id in hexadecimal form, the history in the hex-and-dash form. */
private fun parseProjectId(projectId: String): Uuid? =
    runCatching { Uuid.parse(projectId) }
        .recoverCatching { Uuid.parseHex(projectId) }
        .getOrNull()

private fun TunnelRequestRecord.toRequestUpdate(project: ProjectNames): WebAppServerMessage.RequestUpdate =
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
            projectKey = project.key,
            projectName = project.name,
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

    /**
     * Asks for the page of requests that started no later than [before], or for the newest page if
     * [before] is null.
     */
    @Serializable
    @SerialName("history")
    data class History(
        @SerialName("before") val before: Long? = null,
        @SerialName("limit") val limit: Int = REQUEST_PAGE_SIZE,
    ): WebAppClientMessage()

    /** Asks for the current state of already known requests, to revalidate a client side cache. */
    @Serializable
    @SerialName("sync")
    data class Sync(
        @SerialName("request_ids") val requestIds: List<String>,
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

    /** A page of requests, answering a [WebAppClientMessage.History]. */
    @Serializable
    @SerialName("request.history")
    data class RequestHistory(
        @SerialName("requests") val requests: List<RequestUpdate>,
        /** The cursor the page was asked for, so the client can tell pages apart. */
        @SerialName("before") val before: Long?,
        /** No further page follows, the client can stop asking. */
        @SerialName("complete") val complete: Boolean,
    ): WebAppServerMessage()

    /** The current state of the requests a [WebAppClientMessage.Sync] asked about. */
    @Serializable
    @SerialName("request.sync")
    data class RequestSync(
        @SerialName("requests") val requests: List<RequestUpdate>,
        /** Requests that neither the tunnel nor the database knows; the client drops them. */
        @SerialName("missing") val missing: List<String>,
    ): WebAppServerMessage()

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
                    projectKey = request.project.projectKey,
                    projectName = request.project.name,
                    serviceName = request.service?.serviceKey,
                ),
                statusCode = (request.result as? TunnelRequestResult.Success)?.statusCode,
                error = (request.result as? TunnelRequestResult.Failure)?.error,
                startedAt = request.startedAt.toEpochMilliseconds(),
                sentToTunnelAt = request.startedAt.toEpochMilliseconds(),
                responseStartedAt = request.responseReadyAt?.toEpochMilliseconds(),
                completedAt = request.responseReadyAt?.toEpochMilliseconds(),
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
        @SerialName("project_key") val projectKey: String,
        @SerialName("project_name") val projectName: String,
        @SerialName("service_name") val serviceName: String?,
    )
}
