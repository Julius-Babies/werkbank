package app.werkbank.app.webapp.projects

import app.werkbank.database.DatabaseManager
import app.werkbank.plugins.auth.UserPrincipal
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.webappProjects() {

    val db by inject<DatabaseManager>()

    authenticate("jwt") {
        get {
            val principal = call.principal<UserPrincipal>()!!

            db.query {
                principal.user.projects
                    .sortedByDescending { it.createdAt }
                    .map { project ->
                        ProjectResponse(
                            projectId = project.id.value,
                            projectKey = project.projectKey,
                            projectName = project.name,
                            createdAt = project.createdAt.toEpochMilliseconds()
                        )
                    }
            }.let {
                call.respond(it)
            }
        }
    }
}

@Serializable
private data class ProjectResponse(
    @SerialName("project_id") val projectId: Uuid,
    @SerialName("project_key") val projectKey: String,
    @SerialName("project_name") val projectName: String,
    @SerialName("created_at") val createdAt: Long,
)