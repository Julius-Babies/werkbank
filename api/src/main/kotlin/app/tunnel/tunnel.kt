package app.werkbank.app.tunnel

import app.werkbank.database.User
import app.werkbank.plugins.auth.UserPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*

val tunnels = mutableMapOf<User.Id, DefaultWebSocketServerSession>()

fun Route.tunnel() {
    authenticate("jwt") {
        webSocket {
            val user = call.principal<UserPrincipal>()!!
            tunnels[user.user.id.value] = this

            runCatching {
                incoming.receive()
            }.also {
                tunnels.remove(user.user.id.value)
            }
        }
    }
}
