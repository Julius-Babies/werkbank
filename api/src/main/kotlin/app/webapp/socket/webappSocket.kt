package app.werkbank.app.webapp.socket

import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.plugins.auth.UserPrincipal
import com.google.gson.Gson
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

fun Route.webappSocket() {

    val tunnelManager by inject<TunnelManager>()

    authenticate("jwt") {
        webSocket {
            val principal = call.principal<UserPrincipal>()!!

            val tunnelUpdateChannel = tunnelManager
                .subscribeToTunnel(principal.user)

            launch {
                tunnelUpdateChannel
                    .receiveAsFlow()
                    .collect { tunnel ->
                        if (tunnel != null) {
                            sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelActive)
                        } else {
                            sendSerialized<WebAppServerMessage>(WebAppServerMessage.TunnelInactive)
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
    data object TunnelActive: WebAppServerMessage()

    @Serializable
    @SerialName("tunnel.inactive")
    data object TunnelInactive: WebAppServerMessage()
}