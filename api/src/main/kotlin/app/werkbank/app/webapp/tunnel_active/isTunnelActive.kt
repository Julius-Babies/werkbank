package app.werkbank.app.webapp.tunnel_active

import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.User
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.isTunnelActive() {

    val tunnelManager by inject<TunnelManager>()
    val db by inject<DatabaseManager>()
    val config by inject<AppConfig>()

    get {
        val userIdRaw = call.request.queryParameters["user_id"]

        if (userIdRaw == null) {
            call.respondText("Missing userId parameter", status = HttpStatusCode.BadRequest)
            return@get
        }

        val userId = Uuid.parseOrNull(userIdRaw)
        if (userId == null) {
            call.respondText("Invalid userId parameter", status = HttpStatusCode.BadRequest)
            return@get
        }

        val user = db.query { User.findById(userId) }
        if (user == null) {
            call.respondText("User not found", status = HttpStatusCode.NotFound)
            return@get
        }

        val isActive = tunnelManager.getTunnel(user) != null

        /**
         * CORS headers are set to allow requests from the app domain and its subdomains.
         * This route is being queried by the proxy if the tunnel is not active so we can inform the user as soon
         * as the tunnel is active again. These error pages however are served directly on the requested project page,
         * so their origin is the project domain, which is guaranteed to be a subdomain of the app domain.
         */
        val requestOrigin = call.request.headers[HttpHeaders.Origin]
        if (requestOrigin != null && Url(requestOrigin).host.endsWith("." + config.appDomain))
            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
        call.respond(TunnelActiveResponse(isActive))
    }
}

@Serializable
data class TunnelActiveResponse(@SerialName("is_active") val isActive: Boolean)