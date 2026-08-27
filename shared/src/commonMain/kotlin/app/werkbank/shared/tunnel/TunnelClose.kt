package app.werkbank.shared.tunnel

/**
 * Close reason the server sends when it refuses a tunnel because another one is already connected
 * for the same account. Sent with the WebSocket close code 1008 (violated policy); the CLI matches
 * on this string to tell a rejection apart from an ordinary disconnect.
 */
const val TUNNEL_ALREADY_RUNNING_REASON = "A tunnel is already running for this account"

/**
 * How much of a close reason fits into a WebSocket close frame: RFC 6455 §5.5 caps a control frame
 * at 125 bytes, two of which the close code takes.
 */
const val MAX_CLOSE_REASON_BYTES = 123

/**
 * Cuts this text down to what a WebSocket close frame can carry.
 *
 * Reasons that travel through the tunnel come from HTTP engines and their messages run into hundreds
 * of bytes; ktor refuses to build a close frame from those. That throw would land in the reader loop
 * of whichever side relays the close — and taking the whole multiplexer down because one proxied
 * connection failed is exactly what must not happen. The untruncated text stays available where it
 * has room: the request inspector, the tunnel log and the proxy error page.
 */
fun String.toCloseReasonMessage(): String {
    if (encodeToByteArray().size <= MAX_CLOSE_REASON_BYTES) return this
    // A char encodes to up to 3 bytes (4 for a surrogate pair), so the byte budget is only an upper
    // bound on the number of chars that fit.
    var end = minOf(length, MAX_CLOSE_REASON_BYTES - ELLIPSIS_BYTES)
    while (end > 0) {
        // Cutting between the halves of a surrogate pair would leave an unencodable lone surrogate.
        if (this[end - 1].isHighSurrogate()) {
            end--
            continue
        }
        if (substring(0, end).encodeToByteArray().size <= MAX_CLOSE_REASON_BYTES - ELLIPSIS_BYTES) break
        end--
    }
    return substring(0, end) + ELLIPSIS
}

private const val ELLIPSIS = "…"
private val ELLIPSIS_BYTES = ELLIPSIS.encodeToByteArray().size
