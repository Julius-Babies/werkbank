package app.werkbank.app.projects.item.access.password.item

import app.werkbank.app.projects.item.getProjectWithPrincipalAsOwner
import app.werkbank.database.DatabaseManager
import app.werkbank.plugins.auth.AUTH_USER_JWT
import io.ktor.server.auth.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import org.koin.ktor.ext.inject

fun Route.deletePassword() {

    val db by inject<DatabaseManager>()

    authenticate(AUTH_USER_JWT) {
        delete {
            call.getProjectWithPrincipalAsOwner() ?: return@delete
            val password = call.getProjectPassword() ?: return@delete

            db.query {
                password.delete()
            }

            call.respondText("Ok")
        }
    }
}