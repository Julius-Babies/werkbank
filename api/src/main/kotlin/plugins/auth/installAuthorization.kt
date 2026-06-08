package app.werkbank.plugins.auth

import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.User
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
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