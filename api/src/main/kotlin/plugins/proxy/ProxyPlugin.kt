package app.werkbank.plugins.proxy

import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.config.AppConfig
import app.werkbank.database.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.koin.ktor.ext.inject

val SubdomainHandler = createApplicationPlugin(name = "SubdomainHandler") {
    val appConfig by application.inject<AppConfig>()
    val db by application.inject<DatabaseManager>()
    val tunnelManager by application.inject<TunnelManager>()

    val suffix = appConfig.domainSuffix
    val regex = Regex("(.+\\.){2}${suffix.replace(".", "\\.")}")

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

            val tunnel = tunnelManager.getTunnel(user)

            if (tunnel == null) {
                call.respondText("Tunnel not active, start with wb tunnel.", status = HttpStatusCode.ServiceUnavailable)
                return@onCall
            }

            val project: Project
            val service: Service?

            if ('-' in destination) {
                val (serviceName, projectName) = destination.split('-', limit = 2)

                val requestedProject = db.query { Project.find { (Projects.name.lowerCase() eq projectName.lowercase()) and (Projects.owner eq user.id) }.firstOrNull() }
                if (requestedProject == null) {
                    call.respondText("Project not found", status = HttpStatusCode.NotFound)
                    return@onCall
                }
                project = requestedProject

                val requestedService = db.query { Service.find { Services.project eq project.id and (Services.serviceKey.lowerCase() eq serviceName.lowercase()) }.firstOrNull() }
                if (requestedService == null) {
                    call.respondText("Service not found", status = HttpStatusCode.NotFound)
                    return@onCall
                }
                service = requestedService
            } else {
                val requestedProject = db.query { Project.find { (Projects.name.lowerCase() eq destination.lowercase()) and (Projects.owner eq user.id) }.firstOrNull() }
                if (requestedProject == null) {
                    call.respondText("Project not found", status = HttpStatusCode.NotFound)
                    return@onCall
                }
                project = requestedProject
                service = null
            }

            // TODO: Check auth + service openness

            val result = tunnel.request(
                method = call.request.httpMethod,
                projectName = project.projectKey,
                serviceName = service?.serviceKey,
                path = call.request.uri,
                headers = call.request.headers.toMap(),
                body = when (call.request.httpMethod) {
                    HttpMethod.Get -> null
                    else -> call.receiveChannel()
                },
                coroutineScope = CoroutineScope(currentCoroutineContext())
            )


            val response = result.await()
            response.headers.forEach { (key, values) ->
                values.forEach { call.response.headers.append(key, it) }
            }
            call.respondOutputStream(
                status = response.status,
                producer = { response.body?.copyTo(this) }
            )
        }
    }
}
