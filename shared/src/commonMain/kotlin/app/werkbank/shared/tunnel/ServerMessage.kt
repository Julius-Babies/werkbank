package app.werkbank.shared.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class ServerMessage {

    @Serializable
    @SerialName("http.new_request")
    data class HttpRequest(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("project") val project: String,
        @SerialName("service") val service: String?,
        @SerialName("path") val path: String,
        @SerialName("method") val method: String,
        @SerialName("headers") val headers: List<String>,
    ): ServerMessage()

    @Serializable
    @SerialName("http.body")
    data class HttpBody(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("body") val body: String,
    ): ServerMessage()

    @Serializable
    @SerialName("http.end")
    data class HttpEnd(
        @SerialName("request_id") val requestId: Uuid,
    ): ServerMessage()
}