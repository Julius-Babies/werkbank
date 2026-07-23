package app.werkbank.app.webapp.socket

import app.werkbank.app.tunnel.RequestId
import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.app.tunnel.TunnelRequestRecord
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

fun Route.webappSocket() {

    val tunnelManager by inject<TunnelManager>()

    authenticate(AUTH_USER_JWT) {
        webSocket {
            val principal = call.principal<UserPrincipal>()!!

            var activeTunnelJob: Job? = null
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

                        // Observe every request individually. A ProxyRequest's snapshot StateFlow replays
                        // its current state on subscription, so the history is sent automatically and every
                        // later phase transition streams through as a RequestUpdate.
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

            incoming.receiveAsFlow().collect()
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
    )

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
        @SerialName("method") val method: String,
        @SerialName("uri") val uri: String,
        @SerialName("target") val target: RequestTarget?,
        @SerialName("status_code") val statusCode: Int?,
        @SerialName("error") val error: String?,
        @SerialName("started_at") val startedAt: Long,
        @SerialName("sent_to_tunnel_at") val sentToTunnelAt: Long?,
        @SerialName("response_started_at") val responseStartedAt: Long?,
        @SerialName("completed_at") val completedAt: Long?,
    ): WebAppServerMessage() {
        companion object {
            fun from(request: TunnelRequest): RequestUpdate = RequestUpdate(
                requestId = request.id.value.toString(),
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
            )
        }
    }

    @Serializable
    data class RequestTarget(
        @SerialName("project_id") val projectId: String,
        @SerialName("project_name") val projectName: String,
        @SerialName("service_name") val serviceName: String?,
    )
}