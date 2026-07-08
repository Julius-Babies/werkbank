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

    @Serializable
    @SerialName("ws.open")
    data class WsOpen(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("project") val project: String,
        @SerialName("service") val service: String?,
        @SerialName("path") val path: String,
        @SerialName("headers") val headers: List<String>,
    ): ServerMessage()

    @Serializable
    @SerialName("ws.text")
    data class WsText(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("text") val text: String,
    ): ServerMessage()

    @Serializable
    @SerialName("ws.binary")
    data class WsBinary(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("fin") val fin: Boolean,
        @SerialName("body") val body: String,
    ): ServerMessage()

    @Serializable
    @SerialName("ws.close")
    data class WsClose(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("code") val code: Int,
        @SerialName("reason") val reason: String,
    ): ServerMessage()

    @Serializable
    @SerialName("ping")
    data class Ping(
        @SerialName("request_id") val requestId: Uuid,
    ): ServerMessage()

    @Serializable
    @SerialName("pong")
    data class Pong(
        @SerialName("request_id") val requestId: Uuid,
    ): ServerMessage()
}