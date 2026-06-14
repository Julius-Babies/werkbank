package app.werkbank.app.proxy.auth.password

import app.werkbank.app.proxy.auth.tokenMap
import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.plugins.proxy.proxyAuthSessions
import app.werkbank.util.sha256
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

fun Route.proxyPassword() {

    val db by inject<DatabaseManager>()
    val appConfig by inject<AppConfig>()

    post {
        val proxyAuthSessionId = call.parameters["proxy_auth_session_id"]?.let { Uuid.parse(it) } ?: return@post call.respondText("No proxy_auth_session_id provided", status = HttpStatusCode.BadRequest)
        val proxyAuthSession = proxyAuthSessions[proxyAuthSessionId] ?: return@post call.respondText("No proxyAuthSession found for proxyAuthSessionId", status = HttpStatusCode.NotFound)
        val request = call.receive<ProxyPasswordRequest>()

        val usedPassword = db.query {
            val passwords = proxyAuthSession.project.passwords.toList()
            val usedPassword = passwords.firstOrNull { password ->
                password.password.passwordHash == request.password.sha256()
            }

            usedPassword
        }

        if (usedPassword == null) {
            return@post call.respondText("Invalid password", status = HttpStatusCode.Unauthorized)
        }

        val tokenValidity = 60.days
        val token = db.query {
            JWT.create()
                .withAudience("werkbank-project-${proxyAuthSession.project.id.value.toHexString()}")
                .withIssuer("werkbank")
                .withClaim("source", "password")
                .withClaim("password_id", usedPassword.password.id.value.toHexString())
                .withClaim("project_id", proxyAuthSession.project.id.value.toHexString())
                .withExpiresAt((Clock.System.now() + tokenValidity).toJavaInstant())
                .sign(Algorithm.HMAC256(appConfig.jwt.secret))
        }

        tokenMap[proxyAuthSessionId] = token

        val userDomain = db.query { proxyAuthSession.project.owner.username.lowercase() + "." + appConfig.domainSuffix }
        call.respond(ProxyPasswordResult("https://$userDomain/api/proxy/auth/result?proxy_auth_session_id=$proxyAuthSessionId"))
    }
}


@Serializable
private data class ProxyPasswordRequest(
    @SerialName("password") val password: String,
)

@Serializable
private data class ProxyPasswordResult(
    @SerialName("redirect_uri") val redirectUri: String,
)