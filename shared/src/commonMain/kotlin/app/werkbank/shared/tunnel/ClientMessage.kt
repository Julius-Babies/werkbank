package app.werkbank.shared.tunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class ClientMessage {

    abstract val requestId: Uuid

    @Serializable
    @SerialName("request.resolved")
    data class RequestResolved(
        @SerialName("request_id") override val requestId: Uuid,
        @SerialName("service") val service: String,
    ): ClientMessage()

    @Serializable
    @SerialName("http.response")
    data class HttpResponse(
        @SerialName("request_id") override val requestId: Uuid,
        @SerialName("status_code") val statusCode: Int,
        @SerialName("headers") val headers: List<String>,
    ): ClientMessage()

    @Serializable
    @SerialName("http.error.timeout")
    data class Timeout(
        @SerialName("request_id") override val requestId: Uuid,
    ): ClientMessage()

    @Serializable
    @SerialName("http.error.server_not_running")
    data class ServerNotRuning(
        @SerialName("request_id") override val requestId: Uuid,
    ): ClientMessage()

    @Serializable
    @SerialName("http.end")
    data class HttpEnd(
        @SerialName("request_id") override val requestId: Uuid,
    ): ClientMessage()

    /**
     * The tunnel host hit an unexpected error while handling the request (e.g. the target could not be
     * resolved, or the local service closed the connection before responding). Carries a human-readable
     * [message] and the [checkpoints] the request reached, so the server can render an "unexpected error"
     * page and persist the failure with its timeline instead of letting the request hang forever.
     */
    @Serializable
    @SerialName("error.unexpected")
    data class UnexpectedError(
        @SerialName("request_id") override val requestId: Uuid,
        @SerialName("message") val message: String,
        @SerialName("checkpoints") val checkpoints: List<TunnelCheckpoint> = emptyList(),
    ): ClientMessage()

    @Serializable
    @SerialName("ws.opened")
    data class WsOpened(
        @SerialName("request_id") override val requestId: Uuid,
        // Handshake (101 Switching Protocols) response headers of the upstream WebSocket, as
        // "Name: Value" lines. Defaulted so older clients that omit it still deserialize.
        @SerialName("headers") val headers: List<String> = emptyList(),
    ): ClientMessage()

    @Serializable
    @SerialName("ws.text")
    data class WsText(
        @SerialName("request_id") override val requestId: Uuid,
        @SerialName("text") val text: String,
    ): ClientMessage()

    @Serializable
    @SerialName("ws.close")
    data class WsClose(
        @SerialName("request_id") override val requestId: Uuid,
        @SerialName("code") val code: Int,
        @SerialName("reason") val reason: String,
    ): ClientMessage()

    @Serializable
    @SerialName("ping")
    data class Ping(
        @SerialName("request_id") override val requestId: Uuid,
    ): ClientMessage()

    @Serializable
    @SerialName("pong")
    data class Pong(
        @SerialName("request_id") override val requestId: Uuid,
    ): ClientMessage()
}
