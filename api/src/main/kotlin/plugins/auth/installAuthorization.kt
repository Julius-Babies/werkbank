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
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Application.installAuthorization() {

    val appConfig by inject<AppConfig>()
    val db by inject<DatabaseManager>()

    install(Authentication) {
        jwt("jwt") {
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

                val cookie = call.request.cookies["werkbank-token"]?.ifBlank { null }
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
    }
}

data class UserPrincipal(
    val user: User
)