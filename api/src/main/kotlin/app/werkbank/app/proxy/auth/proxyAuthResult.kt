package app.werkbank.app.proxy.auth

import app.werkbank.plugins.proxy.proxyAuthSessions
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.host
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

fun Route.proxyAuthResult() {
    get {
        val proxyAuthSessionId = call.parameters["proxy_auth_session_id"]?.let { Uuid.parse(it) } ?: return@get call.respondText("No proxy_auth_session_id provided", status = HttpStatusCode.BadRequest)
        val proxyAuthSession = proxyAuthSessions[proxyAuthSessionId] ?: return@get call.respondText("No proxyAuthSession found for proxyAuthSessionId", status = HttpStatusCode.NotFound)
        val token = tokenMap[proxyAuthSessionId] ?: return@get call.respondText("No token found for proxyAuthSessionId", status = HttpStatusCode.NotFound)
        call.response.cookies.append(Cookie(
            name = "werkbank-project-auth-token-${proxyAuthSession.project.id.value.toHexString()}",
            value = token,
            path = "/",
            maxAge = 60.days.inWholeSeconds.toInt(),
            domain = call.request.host(),
            secure = true,
            httpOnly = true,
        ))

        tokenMap.remove(proxyAuthSessionId)
        proxyAuthSessions.remove(proxyAuthSessionId)

        call.respondRedirect("https://${proxyAuthSession.host}${proxyAuthSession.path}", permanent = false)
    }
}