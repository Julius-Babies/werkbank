package app.werkbank.plugins.subdomain

import app.werkbank.app.tunnel.tunnels
import app.werkbank.config.AppConfig
import app.werkbank.database.*
import app.werkbank.shared.tunnel.ServerMessage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import io.ktor.utils.io.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.koin.ktor.ext.inject
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

val SubdomainHandler = createApplicationPlugin(name = "SubdomainHandler") {
    val appConfig by application.inject<AppConfig>()
    val db by application.inject<DatabaseManager>()

    val suffix = appConfig.domainSuffix
    val regex = Regex(".+\\.${suffix.replace(".", "\\.")}")

    onCall { call ->
        val host = call.request.host()
        if (host == appConfig.appDomain) return@onCall
        if (regex.matches(host)) {
            val domain = host.removeSuffix(".$suffix")
            val (destination, username) = domain.split(".", limit = 2)

            val user = db.query { User.find { Users.username.lowerCase() eq username.lowercase() }.firstOrNull() }
            if (user == null) {
                call.respondText("User not found", status = HttpStatusCode.NotFound)
                return@onCall
            }

            val tunnel = tunnels[user.id.value]

            if (tunnel == null) {
                call.respondText("Tunnel not active, start with wb tunnel.", status = HttpStatusCode.ServiceUnavailable)
                return@onCall
            }

            if ('-' in destination) {
                val (serviceName, projectName) = destination.split('-', limit = 2)

                val project = db.query { Project.find { (Projects.name.lowerCase() eq projectName.lowercase()) and (Projects.owner eq user.id) }.firstOrNull() }
                if (project == null) {
                    call.respondText("Project not found", status = HttpStatusCode.NotFound)
                    return@onCall
                }

                val service = db.query { Service.find { Services.project eq project.id and (Services.serviceKey.lowerCase() eq serviceName.lowercase()) }.firstOrNull() }
                if (service == null) {
                    call.respondText("Service not found", status = HttpStatusCode.NotFound)
                    return@onCall
                }

                // TODO: Check auth + service openness

                val callMethod = call.request.httpMethod
                val callPath = call.request.uri
                val callHeaders = call.request.headers.toMap()
                val requestId = Uuid.random()

                tunnel.sendSerialized<ServerMessage>(ServerMessage.HttpRequest(
                    requestId = requestId,
                    project = projectName,
                    service = serviceName,
                    path = callPath,
                    method = callMethod.value,
                    headers = callHeaders.flatMap { (key, values) ->
                        values.map { "$key: $it" }
                    }
                ))

                call
                    .receiveChannel()
                    .base64Chunks { chunk ->
                        tunnel.sendSerialized<ServerMessage>(ServerMessage.HttpBody(
                            requestId = requestId,
                            body = chunk
                        ))
                    }

                tunnel.sendSerialized<ServerMessage>(ServerMessage.HttpEnd(
                    requestId = requestId
                ))

                call.respondText("""
                    Proxying call to wb tunnel. Props:
                    - Method: $callMethod
                    - Path: $callPath
                    - Headers: $callHeaders
                """.trimIndent())
            }
        }
    }
}

suspend fun ByteReadChannel.base64Chunks(
    chunkSize: Int = 1024 * 1024,
    emit: suspend (String) -> Unit
) {
    val buffer = ByteArray(chunkSize)
    var remainder = ByteArray(0)

    while (!isClosedForRead) {
        val read = readAvailable(buffer)
        if (read <= 0) continue

        val combined = ByteArray(remainder.size + read)
        remainder.copyInto(combined)
        buffer.copyInto(combined, destinationOffset = remainder.size, endIndex = read)

        val encodableLength = (combined.size / 3) * 3
        val toEncode = combined.copyOfRange(0, encodableLength)

        remainder = combined.copyOfRange(encodableLength, combined.size)

        if (toEncode.isNotEmpty()) {
            emit(Base64.encode(toEncode))
        }
    }

    if (remainder.isNotEmpty()) {
        emit(Base64.encode(remainder))
    }
}