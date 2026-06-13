package app.werkbank.app.projects.item.access.password.item

import app.werkbank.app.projects.item.getProject
import app.werkbank.database.DatabaseManager
import app.werkbank.database.ProjectPassword
import app.werkbank.database.ProjectPasswords
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

suspend fun ApplicationCall.getProjectPassword(): ProjectPassword? {
    val db by application.inject<DatabaseManager>()
    val project = this.getProject() ?: return null

    val passwordId = parameters["passwordId"]?.let { Uuid.parse(it) } ?: run {
        this.respondText("Missing password key", status = HttpStatusCode.BadRequest)
        return null
    }

    val password = db.query {
        ProjectPassword
            .find { ProjectPasswords.projectId eq project.id.value and (ProjectPasswords.passwordId eq passwordId) }
            .firstOrNull()
    }

    if (password == null) {
        this.respondText("Password not found", status = HttpStatusCode.NotFound)
        return null
    }

    return password
}