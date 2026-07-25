package app.werkbank.plugins.proxy

import app.werkbank.database.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import io.ktor.websocket.SimpleFrameCollector
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.koin.ktor.ext.inject

suspend fun ApplicationCall.getProjectAndServiceFromRequest(): ProjectResolveResult {
    val db by inject<DatabaseManager>()
    val user = attributes[currentUser]
    val destination = attributes[requestedDestination]

    val (serviceKey, projectKey) = if ('-' in destination) destination.split('-', limit = 2).let { Pair(it[0], it[1]) }
    else null to destination

    val project = db.query {
        Project.find { (Projects.projectKey.lowerCase() eq projectKey.lowercase()) and (Projects.owner eq user.id) }
            .firstOrNull()
    }
        ?: return ProjectResolveResult.Failure.ProjectNotFound

    val service = serviceKey?.let { serviceKey ->
        db.query {
            Service.find { Services.project eq project.id and (Services.serviceKey.lowerCase() eq serviceKey.lowercase()) }
                .firstOrNull()
        } ?: return ProjectResolveResult.Failure.ServiceNotFound
    }

    return ProjectResolveResult.Success(project, service)
}

sealed class ProjectResolveResult {
    data class Success(val project: Project, val service: Service?) : ProjectResolveResult()
    sealed class Failure : ProjectResolveResult(), SimpleError {
        data object ProjectNotFound : Failure() {
            override val message: String = "The requested project does not exist."
            override val code: String = "ERR_PROJECT_NOT_FOUND"
        }

        data object ServiceNotFound : Failure() {
            override val message: String = "The requested service does not exist for this project."
            override val code: String = "ERR_SERVICE_NOT_FOUND"
        }
    }
}

interface SimpleError {
    val message: String
    val code: String

    suspend fun respondIn(call: ApplicationCall, status: HttpStatusCode = HttpStatusCode.InternalServerError) {
        call.respond(
            status = status,
            message = buildMap {
                put("type", "error")
                put("code", code)
                put("message", message)
            }
        )
    }
}