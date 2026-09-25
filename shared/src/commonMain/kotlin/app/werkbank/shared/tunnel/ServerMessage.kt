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
    @SerialName("ws.close")
    data class WsClose(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("code") val code: Int,
        @SerialName("reason") val reason: String,
    ): ServerMessage()

    /**
     * The browser is gone or the server gave up on the request: the tunnel host can stop streaming its
     * response. Only sent with flow control enabled, as older clients don't know it.
     */
    @Serializable
    @SerialName("http.cancel")
    data class HttpCancel(
        @SerialName("request_id") val requestId: Uuid,
    ): ServerMessage()

    /**
     * The server's first message on a tunnel whose client announced [TunnelFlowControl.HEADER]:
     * flow control is on for this connection. Its absence means an older server without it.
     */
    @Serializable
    @SerialName("flow.enabled")
    data object FlowControl: ServerMessage()

    /** Returns flow control credit; see [TunnelFlowControl.onCredit]. */
    @Serializable
    @SerialName("flow.credit")
    data class Credit(
        @SerialName("request_id") val requestId: Uuid,
        @SerialName("bytes") val bytes: Long,
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