package app.werkbank.app.webapp.projects

import app.werkbank.app.webapp.projects.ProjectResponse.AccessState
import app.werkbank.app.webapp.projects.ProjectResponse.AccessState.Disabled
import app.werkbank.app.webapp.projects.ProjectResponse.AccessState.Open
import app.werkbank.app.webapp.projects.ProjectResponse.AccessState.Restricted
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Project
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
                            createdAt = project.createdAt.toEpochMilliseconds(),
                            accessState = AccessState.fromModel(project.accessState)
                        )
                    }
            }.let {
                call.respond(it)
            }
        }
    }
}

@Serializable
data class ProjectResponse(
    @SerialName("project_id") val projectId: Uuid,
    @SerialName("project_key") val projectKey: String,
    @SerialName("project_name") val projectName: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("access_state") val accessState: AccessState,
) {
    @Serializable
    enum class AccessState {
        @SerialName("disabled") Disabled,
        @SerialName("restricted") Restricted,
        @SerialName("open") Open;
    }
}

fun AccessState.Companion.fromModel(model: Project.AccessState): AccessState {
    return when (model) {
        Project.AccessState.Disabled -> Disabled
        Project.AccessState.Restricted -> Restricted
        Project.AccessState.Open -> Open
    }
}

fun AccessState.toModel(): Project.AccessState {
    return when (this) {
        Disabled -> Project.AccessState.Disabled
        Restricted -> Project.AccessState.Restricted
        Open -> Project.AccessState.Open
    }
}