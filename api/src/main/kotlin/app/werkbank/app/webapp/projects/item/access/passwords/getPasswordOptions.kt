package app.werkbank.app.webapp.projects.item.access.passwords

import app.werkbank.app.projects.item.getProjectWithPrincipalAsOwner
import app.werkbank.database.AccessPassword
import app.werkbank.database.AccessPasswords
import app.werkbank.database.DatabaseManager
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.getPasswordOptions() {

    val db by inject<DatabaseManager>()

    authenticate("jwt") {
        get {
            val project = call.getProjectWithPrincipalAsOwner() ?: return@get
            db.query {
                AccessPassword
                    .find { (AccessPasswords.createdBy eq project.owner.id.value) }
                    .filter { accessPassword -> accessPassword.projects.none { projectPassword -> projectPassword.project.id.value == project.id.value } }
                    .map { accessPassword ->
                        GetPasswordOptionsResponse(accessPassword.id.value, accessPassword.label)
                    }
            }.let {
                call.respond(it)
            }
        }
    }
}

@Serializable
private data class GetPasswordOptionsResponse(
    @SerialName("id") val id: Uuid,
    @SerialName("label") val label: String,
)