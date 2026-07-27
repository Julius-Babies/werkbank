package app.werkbank.shared.tunnel

import kotlin.uuid.Uuid

/**
 * Wire format for binary tunnel frames, shared by CLI and API so both agree byte-for-byte.
 *
 * Payloads that can be large or arbitrary bytes — HTTP request/response bodies and WebSocket binary
 * frames — travel as raw WebSocket binary frames rather than base64 inside a JSON message, which
 * avoids the ~33% size inflation and the encode/decode CPU on both ends.
 *
 * Layout: `[16 bytes request id][1 byte flags][payload…]`. The flags byte tags what the payload is,
 * so the receiver can route it without a side lookup: HTTP body chunks and WebSocket binary frames
 * share the same binary channel but for different request ids. [FLAG_FIN] carries the WebSocket FIN
 * bit so fragmented binary messages survive the relay.
 */
object TunnelFrame {
    /** Payload is a WebSocket binary frame (else an HTTP body chunk). */
    const val FLAG_WEBSOCKET = 0x01

    /** WebSocket FIN bit; only meaningful together with [FLAG_WEBSOCKET]. */
    const val FLAG_FIN = 0x02

    const val HEADER_SIZE = 17

    /** Builds `flags` for a WebSocket binary frame with the given FIN bit. */
    fun webSocketFlags(fin: Boolean): Int = FLAG_WEBSOCKET or (if (fin) FLAG_FIN else 0)

    /** Encodes a full frame: request id + flags header followed by [payload]. */
    fun encode(requestId: Uuid, flags: Int, payload: ByteArray, length: Int = payload.size): ByteArray {
        val frame = ByteArray(HEADER_SIZE + length)
        requestId.toByteArray().copyInto(frame, 0)
        frame[16] = flags.toByte()
        payload.copyInto(frame, HEADER_SIZE, 0, length)
        return frame
    }

    fun requestId(frame: ByteArray): Uuid = Uuid.fromByteArray(frame.copyOfRange(0, 16))

    fun isWebSocket(frame: ByteArray): Boolean = (frame[16].toInt() and FLAG_WEBSOCKET) != 0

    fun isFin(frame: ByteArray): Boolean = (frame[16].toInt() and FLAG_FIN) != 0

    fun payload(frame: ByteArray): ByteArray = frame.copyOfRange(HEADER_SIZE, frame.size)
}
