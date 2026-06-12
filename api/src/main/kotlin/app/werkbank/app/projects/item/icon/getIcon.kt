package app.werkbank.app.projects.item.icon

import app.werkbank.app.projects.item.getProjectWithPrincipalAsOwner
import app.werkbank.database.DatabaseManager
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.getIcon() {

    val db by inject<DatabaseManager>()

    authenticate("jwt") {
        get {
            val project = call.getProjectWithPrincipalAsOwner() ?: return@get
            db.query {
                val blob = project.icon ?: return@query call.respondText("No icon found", status = HttpStatusCode.NotFound)

                call.respondBytes(blob.bytes)
            }
        }
    }
}