package app.werkbank.plugins.auth

import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.User
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.host
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

const val AUTH_USER_JWT = "auth-user-jwt"
const val AUTH_GITHUB_ACTIONS_WERKBANK_REPOSITORY = "auth-github-actions-werkbank-repository"

fun Application.installAuthorization() {

    val appConfig by inject<AppConfig>()
    val db by inject<DatabaseManager>()

    install(Authentication) {
        jwt(AUTH_USER_JWT) {
            realm = "werkbank-jwt"
            verifier(
                JWT
                    .require(Algorithm.HMAC256(appConfig.jwt.secret))
                    .withAudience("werkbank")
                    .withIssuer("werkbank")
                    .build()
            )

            authHeader { call ->
                val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.ifBlank { null }
                if (token != null) {
                    return@authHeader parseAuthorizationHeader("Bearer $token")
                }

                val cookieName = if (call.request.host() == appConfig.appDomain) "wbcloud-token" else "werkbank-token"

                val cookie = call.request.cookies[cookieName]?.ifBlank { null }
                if (cookie != null) {
                    return@authHeader parseAuthorizationHeader("Bearer $cookie")
                }

                return@authHeader null
            }

            validate { credential ->
                val userId = Uuid.parse(credential.payload.getClaim("sub").asString())
                val user = db.query { User.findById(userId) }
                if (user != null) {
                    UserPrincipal(user)
                } else {
                    null
                }
            }
        }

        bearer(AUTH_GITHUB_ACTIONS_WERKBANK_REPOSITORY) {
            realm = "werkbank-github-actions-repository"
            authenticate { token ->
                if (token.token != appConfig.github.webhookCliUpdateBearer) return@authenticate null
                return@authenticate UserIdPrincipal("github-actions-werkbank-cli-update")
            }
        }
    }
}

data class UserPrincipal(
    val user: User
)