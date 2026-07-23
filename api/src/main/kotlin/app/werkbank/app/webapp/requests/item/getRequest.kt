package app.werkbank.app.webapp.requests.item

import app.werkbank.database.*
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.getRequest() {
    val db by inject<DatabaseManager>()

    authenticate(AUTH_USER_JWT) {
        get {
            val request = call.getRequestWithPrincipalAsOwner() ?: return@get

            db.query {
                RequestResponse(
                    requestId = request.id.value,
                    method = request.method,
                    uri = request.uri,
                    target = RequestResponse.Target(
                        projectId = request.project.id.value,
                        projectName = request.project.name,
                        serviceId = request.service?.id?.value,
                        serviceName = request.service?.serviceKey,
                    ),
                    requestHeaders = request.requestHeaders,
                    requestBodySize = if (request.requestBody != null) TunnelRequests
                        .select(TunnelRequests.requestBody.octetLength())
                        .where { TunnelRequests.id eq request.id }
                        .map { it[TunnelRequests.requestBody.octetLength()] }
                        .singleOrNull() ?: 0 else 0,
                    responseHeaders = request.responseHeaders.orEmpty(),
                    responseError = (request.result as? TunnelRequestResult.Failure)?.error,
                    responseStatusCode = (request.result as? TunnelRequestResult.Success)?.statusCode,
                    responseBodySize = if (request.responseBody != null) TunnelRequests
                        .select(TunnelRequests.responseBody.octetLength())
                        .where { TunnelRequests.id eq request.id }
                        .map { it[TunnelRequests.responseBody.octetLength()] }
                        .singleOrNull() ?: 0 else 0,
                )
            }.let { call.respond(it) }
        }
    }
}

suspend fun ApplicationCall.getRequestWithPrincipalAsOwner(): TunnelRequest? {
    val db by inject<DatabaseManager>()

    val requestId = parameters["requestId"]?.let { Uuid.parse(it) } ?: run {
        respondText("Missing request id", status = HttpStatusCode.BadRequest)
        return null
    }
    val principal = authentication.principal<UserPrincipal>() ?: run {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return null
    }

    val request = db.query {
        TunnelRequest
            .findById(requestId)
    } ?: run {
        respondText("Request not found", status = HttpStatusCode.NotFound)
        return null
    }

    if (!db.query { request.canBeAccessedBy(principal.user) }) {
        respondText("Request not accessible", status = HttpStatusCode.Forbidden)
        return null
    }

    return request
}

@Serializable
data class RequestResponse(
    @SerialName("request_id") val requestId: Uuid,
    @SerialName("method") val method: String,
    @SerialName("uri") val uri: String,
    @SerialName("target") val target: Target,
    @SerialName("request_headers") val requestHeaders: Map<String, List<String>>,
    @SerialName("request_body_size") val requestBodySize: Long,
    @SerialName("response_status_code") val responseStatusCode: Int?,
    @SerialName("response_error") val responseError: String?,
    @SerialName("response_headers") val responseHeaders: Map<String, List<String>>,
    @SerialName("response_body_size") val responseBodySize: Long
) {
    @Serializable
    data class Target(
        @SerialName("project_id") val projectId: Uuid,
        @SerialName("project_name") val projectName: String,
        @SerialName("service_id") val serviceId: Uuid?,
        @SerialName("service_name") val serviceName: String?,
    )
}