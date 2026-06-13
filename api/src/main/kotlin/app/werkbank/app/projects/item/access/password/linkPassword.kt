package app.werkbank.app.projects.item.access.password

import app.werkbank.app.projects.item.getProjectWithPrincipalAsOwner
import app.werkbank.database.AccessPassword
import app.werkbank.database.DatabaseManager
import app.werkbank.database.ProjectPassword
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.linkPassword() {

    val db by inject<DatabaseManager>()

    authenticate("jwt") {
        post {
            val project = call.getProjectWithPrincipalAsOwner() ?: return@post
            val request = call.receive<LinkPasswordRequest>()
            val passwordId = Uuid.parse(request.passwordId)

            db.query {
                if (project.passwords.any { it.id.value == passwordId }) return@query
                val password = AccessPassword.findById(passwordId) ?: return@query call.respondText("Password not found", status = HttpStatusCode.NotFound)

                ProjectPassword.new {
                    this.project = project
                    this.password = password
                    this.createdBy = project.owner
                }
            }

            call.respondText("Ok")
        }
    }
}

@Serializable
private data class LinkPasswordRequest(
    @SerialName("password_id") val passwordId: String,
)