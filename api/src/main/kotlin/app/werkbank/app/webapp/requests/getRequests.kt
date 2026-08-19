package app.werkbank.app.webapp.requests

import app.werkbank.database.DatabaseManager
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.getRequests() {

    val db by inject<DatabaseManager>()

    authenticate(AUTH_USER_JWT) {
        get {
            val principal = call.principal<UserPrincipal>()!!
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: REQUEST_PAGE_SIZE

            // Only the newest page is sent; older requests are paged in over the WebSocket.
            call.respond(db.requestHistory(principal.user, limit = limit))
        }
    }
}
