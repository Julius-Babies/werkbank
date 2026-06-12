package app.werkbank.app.projects.item

import app.werkbank.app.projects.item.icon.getIcon
import app.werkbank.app.projects.item.icon.setIcon
import app.werkbank.app.projects.item.set_access.setAccess
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Project
import app.werkbank.database.Projects
import app.werkbank.plugins.auth.UserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authentication
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

suspend fun ApplicationCall.getProjectWithPrincipalAsOwner(): Project? {
    val db by inject<DatabaseManager>()

    val projectId = parameters["projectId"]?.let { Uuid.parse(it) } ?: run {
        respondText("Missing project key", status = HttpStatusCode.BadRequest)
        return null
    }
    val principal = authentication.principal<UserPrincipal>() ?: run {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return null
    }

    val project = db.query {
        Project
            .find { (Projects.id eq projectId) and (Projects.owner eq principal.user.id.value) }
            .firstOrNull()
    } ?: run {
        respondText("Project not found", status = HttpStatusCode.NotFound)
        return null
    }

    return project
}


fun Route.projectRoutes() {

    route("/access") {
        setAccess()
    }

    route("/icon") {
        getIcon()
        setIcon()
    }
}