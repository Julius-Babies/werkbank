package app.werkbank.app.webapp.settings.access_keys

import app.werkbank.database.DatabaseManager
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.getAccessKeys() {

    val db by inject<DatabaseManager>()

    authenticate(AUTH_USER_JWT) {
        get {
            val principal = call.principal<UserPrincipal>()!!
            db.query {
                principal.user.accessKeys.map { accessKey ->
                    AccessKeysResponse(
                        id = accessKey.id.value,
                        name = accessKey.name,
                        createdAt = accessKey.createdAt.toEpochMilliseconds()
                    )
                }
            }.let { call.respond(it) }
        }
    }
}

@Serializable
private data class AccessKeysResponse(
    @SerialName("id") val id: Uuid,
    @SerialName("name") val name: String,
    @SerialName("created_at") val createdAt: Long,
)