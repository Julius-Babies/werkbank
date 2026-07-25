package app.werkbank.app.tunnel

/** Direction of a proxied WebSocket frame, seen from the external client (browser). */
enum class WsFrameDirection {
    /** Browser -> dev server (outgoing, "↑"). */
    CLIENT_TO_SERVER,

    /** Dev server -> browser (incoming, "↓"). */
    SERVER_TO_CLIENT,
}

enum class WsFrameOpcode {
    TEXT,
    BINARY,
    CLOSE,
}

/**
 * A single frame captured while proxying a WebSocket connection. Text frames carry [text]; binary
 * frames carry [binaryBase64]. Close frames carry [closeCode]/[closeReason].
 */
data class WsFrameRecord(
    val sequence: Int,
    val direction: WsFrameDirection,
    val opcode: WsFrameOpcode,
    val text: String?,
    val binaryBase64: String?,
    val size: Int,
    val timestamp: Long,
    val closeCode: Int? = null,
    val closeReason: String? = null,
)
