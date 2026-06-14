package app.werkbank.app.webapp.settings.access_keys

import app.werkbank.database.AccessKey
import app.werkbank.database.DatabaseManager
import app.werkbank.plugins.auth.UserPrincipal
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.principal
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.deleteAccessKey() {

    val db by inject<DatabaseManager>()

    authenticate("jwt") {
        delete {
            val principal = call.principal<UserPrincipal>()!!
            val id = call.parameters["accessKeyId"]?.let { Uuid.parse(it) } ?: return@delete call.respondText(
                "No accessKeyId provided",
                status = HttpStatusCode.BadRequest
            )

            db.query {
                val accessKey = AccessKey.findById(id) ?: return@query call.respondText(
                    "Access key not found",
                    status = HttpStatusCode.NotFound
                )

                if (accessKey.createdBy.id.value != principal.user.id.value) {
                    return@query call.respondText(
                        "Access key does not belong to the current user",
                        status = HttpStatusCode.Forbidden
                    )
                }

                accessKey.delete()
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
