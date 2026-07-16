package app.werkbank.app.webapp.socket

import app.werkbank.app.tunnel.TunnelManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds
import org.koin.ktor.ext.inject

fun Route.webappSocket() {

    val tunnelManager by inject<TunnelManager>()

    authenticate(AUTH_USER_JWT) {
        webSocket {
            val principal = call.principal<UserPrincipal>()!!

            val tunnelUpdateChannel = tunnelManager
                .subscribeToTunnel(principal.user)

            var requestUpdatesJob: Job? = null
            launchConnectionJob(call.application, "webapp-tunnel-updates") {
                tunnelUpdateChannel
                    .receiveAsFlow()
                    .collect { tunnel ->
                        requestUpdatesJob?.cancel()
                        requestUpdatesJob = null
                        if (tunnel != null) {
                            sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelActive(pingMs = tunnel.currentPingMs))
                            tunnel.proxyRequests.value.forEach { record ->
                                sendSerialized<WebAppServerMessage>(WebAppServerMessage.RequestUpdate(
                                    requestId = record.requestId.toString(),
                                    method = record.method,
                                    uri = record.uri,
                                    target = WebAppServerMessage.RequestTarget(
                                        projectId = record.projectId,
                                        projectName = record.projectName,
                                        serviceName = record.serviceName,
                                    ),
                                    statusCode = record.statusCode,
                                    error = record.error,
                                    startedAt = record.startedAt,
                                    sentToTunnelAt = record.sentToTunnelAt,
                                    responseStartedAt = record.responseStartedAt,
                                    completedAt = record.completedAt,
                                ))
                            }

                            requestUpdatesJob = launchConnectionJob(call.application, "webapp-request-updates") {
                                tunnel.requestUpdates.collect { record ->
                                    sendSerialized<WebAppServerMessage>(WebAppServerMessage.RequestUpdate(
                                        requestId = record.requestId.toString(),
                                        method = record.method,
                                        uri = record.uri,
                                        target = WebAppServerMessage.RequestTarget(
                                            projectId = record.projectId,
                                            projectName = record.projectName,
                                            serviceName = record.serviceName,
                                        ),
                                        statusCode = record.statusCode,
                                        error = record.error,
                                        startedAt = record.startedAt,
                                        sentToTunnelAt = record.sentToTunnelAt,
                                        responseStartedAt = record.responseStartedAt,
                                        completedAt = record.completedAt,
                                    ))
                                }
                            }
                        } else {
                            sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelInactive)
                        }
                    }
            }

            launchConnectionJob(call.application, "webapp-tunnel-ping") {
                while (isActive) {
                    delay(2.seconds)
                    val tunnel = tunnelManager.getTunnel(principal.user)
                    if (tunnel != null) {
                        sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelActive(pingMs = tunnel.currentPingMs))
                    }
                }
            }

            try {
                incoming.receiveAsFlow().collect()
            } finally {
                tunnelManager.unsubscribeFromTunnel(principal.user, tunnelUpdateChannel)
            }
        }
    }
}

private val gson = Gson()
suspend fun DefaultWebSocketServerSession.sendJson(data: Map<String, Any?>) {
    this.send(gson.toJson(data))
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
                    projectId = request.service.project.id.value.toString(),
                    projectName = request.service.project.name,
                    serviceName = request.service.serviceKey,
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