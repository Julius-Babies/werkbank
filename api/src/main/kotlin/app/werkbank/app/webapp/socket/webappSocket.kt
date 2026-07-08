package app.werkbank.app.webapp.socket

import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import com.google.gson.Gson
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

            launch {
                tunnelUpdateChannel
                    .receiveAsFlow()
                    .collect { tunnel ->
                        if (tunnel != null) {
                            sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelActive(pingMs = tunnel.currentPingMs))
                        } else {
                            sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelInactive)
                        }
                    }
            }

            launch {
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
}