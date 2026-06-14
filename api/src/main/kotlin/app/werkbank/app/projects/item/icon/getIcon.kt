package app.werkbank.app.projects.item.icon

import app.werkbank.app.projects.item.getProject
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Project
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.getIcon() {

    val db by inject<DatabaseManager>()

    authenticate("jwt", optional = true) {
        get {
            val project = call.getProject() ?: return@get
            when (project.accessState) {
                Project.AccessState.Disabled -> return@get call.respondText("Project access is disabled for anonymous users. Please log in to access this project.", status = HttpStatusCode.Unauthorized)
                else -> {}
            }
            db.query {
                val blob = project.icon

                call.respondBytes(blob.bytes)
            }
        }
    }
}