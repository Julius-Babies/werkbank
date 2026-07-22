package app.werkbank.database

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.json
import kotlin.uuid.Uuid

class TunnelRequest(id: EntityID<Uuid>): UuidEntity(id) {
    companion object: UuidEntityClass<TunnelRequest>(TunnelRequests)

    var service by Service optionalReferencedOn TunnelRequests.service
    var project by Project referencedOn TunnelRequests.project
    var method by TunnelRequests.method
    var uri by TunnelRequests.uri
    var requestHeaders by TunnelRequests.requestHeaders
    var responseHeaders by TunnelRequests.responseHeaders
    var result by TunnelRequests.result
    var requestBody by TunnelRequests.requestBody
    var responseBody by TunnelRequests.responseBody
    var startedAt by TunnelRequests.startedAt
    var responseReadyAt by TunnelRequests.responseReadyAt

    fun canBeAccessedBy(user: User): Boolean {
        return service.project.owner.id.value == user.id.value
    }
}

object TunnelRequests : UuidTable("tunnel_requests") {
    private val jsonFormat = Json {
        isLenient = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    val service = reference("service", Services, onDelete = ReferenceOption.CASCADE).nullable()
    val project = reference("project", Projects, onDelete = ReferenceOption.CASCADE)
    val method = varchar("method", 16)
    val uri = varchar("uri", 1024)
    val requestHeaders = json<Map<String, List<String>>>("request_headers", jsonFormat)
    val responseHeaders = json<Map<String, List<String>>>("response_headers", jsonFormat).nullable()
    val result = json<TunnelRequestResult>("result", jsonFormat).nullable()
    val requestBody = blob("request_body").nullable()
    val responseBody = blob("response_body").nullable()
    val startedAt = timestamp("started_at")
    val responseReadyAt = timestamp("response_ready_at").nullable()
}

@Serializable
sealed class TunnelRequestResult {
    @Serializable
    @SerialName("success")
    data class Success(
        @SerialName("status_code") val statusCode: Int
    ): TunnelRequestResult()

    @Serializable
    @SerialName("failure")
    data class Failure(
        @SerialName("error") val error: String
    ): TunnelRequestResult()
}