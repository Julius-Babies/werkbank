package app.werkbank.app.projects.item.access.password

import app.werkbank.app.projects.item.getProjectWithPrincipalAsOwner
import app.werkbank.database.AccessPassword
import app.werkbank.database.DatabaseManager
import app.werkbank.database.ProjectPassword
import app.werkbank.util.sha256
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

fun Route.createPassword() {
    val db by inject<DatabaseManager>()

    authenticate("jwt") {
        post {
            val project = call.getProjectWithPrincipalAsOwner() ?: return@post
            val request = call.receive<CreatePasswordRequest>()

            db.query {
                val password = AccessPassword.new {
                    this.label = request.label
                    this.passwordHash = request.password.sha256()
                    this.createdBy = project.owner
                }

                ProjectPassword.new {
                    this.createdBy = project.owner
                    this.password = password
                    this.project = project
                }
            }

            call.respond("Ok")
        }
    }
}

@Serializable
private data class CreatePasswordRequest(
    @SerialName("label") val label: String,
    @SerialName("password") val password: String,
)