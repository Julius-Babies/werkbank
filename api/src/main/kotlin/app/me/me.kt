package app.werkbank.app.me

import app.werkbank.plugins.auth.UserPrincipal
import es.jvbabi.authentikt.core.utils.buildGenericMap
import es.jvbabi.authentikt.core.utils.respondGson
import io.ktor.server.auth.*
import io.ktor.server.routing.*

fun Route.me() {
    authenticate("jwt") {
        get {
            val principal = call.principal<UserPrincipal>()!!
            call.respondGson(buildGenericMap {
                put("username", principal.user.username)
            })
        }
    }
}