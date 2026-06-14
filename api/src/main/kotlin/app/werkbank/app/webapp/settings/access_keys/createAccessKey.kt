package app.werkbank.app.webapp.settings.access_keys

import app.werkbank.config.AppConfig
import app.werkbank.database.AccessKey
import app.werkbank.database.DatabaseManager
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.util.sha256
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant

val TOKEN_VALIDITY = 365.days

fun Route.createAccessKey() {

    val db by inject<DatabaseManager>()
    val appConfig by inject<AppConfig>()

    authenticate("jwt") {
        post {
            val principal = call.principal<UserPrincipal>()!!
            val request = call.receive<CreateAccessKeyRequest>()

            val accessKey = JWT.create()
                .withAudience("werkbank-projects")
                .withIssuer("werkbank")
                .withClaim("user_id", principal.user.id.value.toHexString())
                .withClaim("sub", principal.user.id.value.toHexString())
                .withExpiresAt((Clock.System.now() + TOKEN_VALIDITY).toJavaInstant())
                .sign(Algorithm.HMAC256(appConfig.jwt.secret))

            db.query {
                AccessKey.new {
                    this.name = request.name
                    this.createdBy = principal.user
                    this.key = accessKey.sha256()
                }
            }

            call.respond(CreateAccessKeyResponse(accessKey))
        }
    }
}

@Serializable
private data class CreateAccessKeyRequest(
    @SerialName("name") val name: String,
)

@Serializable
private data class CreateAccessKeyResponse(
    @SerialName("access_key") val accessKey: String,
)