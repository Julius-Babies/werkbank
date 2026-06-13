package app.werkbank.app.webapp.projects.item.access

import app.werkbank.app.projects.item.getProjectWithPrincipalAsOwner
import app.werkbank.app.webapp.projects.ProjectResponse
import app.werkbank.app.webapp.projects.fromModel
import app.werkbank.database.DatabaseManager
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.getState() {

    val db by inject<DatabaseManager>()

    authenticate("jwt") {
        get {
            val project = call.getProjectWithPrincipalAsOwner() ?: return@get

            db.query {
                GetAccessStateResponse(
                    projectId = project.id.value,
                    projectName = project.name,
                    projectAccessState = ProjectResponse.AccessState.fromModel(project.accessState),
                    projectPasswords = project.passwords.map { password ->
                        GetAccessStateResponse.ProjectPassword(
                            id = password.password.id.value,
                            label = password.password.label,
                            createdAt = password.createdAt.toEpochMilliseconds(),
                        )
                    }
                )
            }.let {
                call.respond(it)
            }
        }
    }
}

@Serializable
data class GetAccessStateResponse(
    @SerialName("project_id") val projectId: Uuid,
    @SerialName("project_name") val projectName: String,
    @SerialName("project_access_state") val projectAccessState: ProjectResponse.AccessState,
    @SerialName("project_passwords") val projectPasswords: List<ProjectPassword>,
) {
    @Serializable
    data class ProjectPassword(
        @SerialName("id") val id: Uuid,
        @SerialName("label") val label: String,
        @SerialName("created_at") val createdAt: Long,
    )
}
