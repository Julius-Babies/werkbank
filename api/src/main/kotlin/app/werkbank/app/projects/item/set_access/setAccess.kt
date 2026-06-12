package app.werkbank.app.projects.item.set_access

import app.werkbank.app.projects.item.getProjectWithPrincipalAsOwner
import app.werkbank.app.webapp.projects.ProjectResponse
import app.werkbank.app.webapp.projects.toModel
import app.werkbank.database.DatabaseManager
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

fun Route.setAccess() {
    val db by inject<DatabaseManager>()

    authenticate("jwt") {
        put {
            val project = call.getProjectWithPrincipalAsOwner() ?: return@put

            val request = call.receive<SetAccessRequest>()
            db.query {
                project.accessState = request.accessState.toModel()
            }

            call.respondText("OK")
        }
    }
}

@Serializable
private data class SetAccessRequest(
    @SerialName("access_state") val accessState: ProjectResponse.AccessState,
)