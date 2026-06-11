package app.werkbank.app.me

import app.werkbank.plugins.auth.UserPrincipal
import es.jvbabi.authentikt.core.utils.buildGenericMap
import es.jvbabi.authentikt.core.utils.respondGson
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.me() {
    authenticate("jwt", optional = true) {
        get {
            val principal = call.principal<UserPrincipal>()

            if (principal == null) {
                call.respond(HttpStatusCode.Unauthorized, "Not authenticated")
                return@get
            }

            call.respondGson(buildGenericMap {
                put("username", principal.user.username)
                put("email", principal.user.email)
                put("avatar_url", principal.user.profileImageUrl)
            })
        }
    }
}