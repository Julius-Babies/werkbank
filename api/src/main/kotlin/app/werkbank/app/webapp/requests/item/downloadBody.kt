package app.werkbank.app.webapp.requests.item

import app.werkbank.database.DatabaseManager
import app.werkbank.plugins.auth.AUTH_USER_JWT
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.koin.ktor.ext.inject

fun Route.downloadTunnelRequestBody() {
    val db by inject<DatabaseManager>()

    authenticate(AUTH_USER_JWT) {
        get {
            val request = call.getRequestWithPrincipalAsOwner() ?: return@get
            val type = call.parameters["type"] ?: return@get

            db.query {
                val stream = when (type) {
                    "request" -> {
                        val body = request.requestBody ?: return@query call.respondText(
                            "No request body",
                            status = HttpStatusCode.NotFound
                        )

                        body.inputStream
                    }
                    "response" -> {
                        val body = request.responseBody ?: return@query call.respondText(
                            "No response body",
                            status = HttpStatusCode.NotFound
                        )
                        body.inputStream
                    }
                    else -> {
                        return@query call.respondText(
                            "Invalid type",
                            status = HttpStatusCode.BadRequest
                        )
                    }
                }

                call.respondOutputStream {
                    stream.use { it.copyTo(this) }
                }
            }

        }
    }
}