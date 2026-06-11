package app.werkbank.shared.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class ClientMessage {

    @Serializable
    @SerialName("http.response")
    data class HttpResponse(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("status_code") val statusCode: Int,
        @SerialName("headers") val headers: List<String>,
    ): ClientMessage()

    @Serializable
    @SerialName("http.body")
    data class HttpBody(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("body") val body: String,
    ): ClientMessage()

    @Serializable
    @SerialName("http.end")
    data class HttpEnd(
        @SerialName("request_id") val requestId: Uuid,
    ): ClientMessage()

    @Serializable
    @SerialName("ws.opened")
    data class WsOpened(
        @SerialName("request_id") val requestId: Uuid,
    ): ClientMessage()

    @Serializable
    @SerialName("ws.text")
    data class WsText(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("text") val text: String,
    ): ClientMessage()

    @Serializable
    @SerialName("ws.binary")
    data class WsBinary(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("body") val body: String,
    ): ClientMessage()

    @Serializable
    @SerialName("ws.close")
    data class WsClose(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("code") val code: Int,
        @SerialName("reason") val reason: String,
    ): ClientMessage()
}
