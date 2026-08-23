package app.werkbank.shared.tunnel

/**
 * Close reason the server sends when it refuses a tunnel because another one is already connected
 * for the same account. Sent with the WebSocket close code 1008 (violated policy); the CLI matches
 * on this string to tell a rejection apart from an ordinary disconnect.
 */
const val TUNNEL_ALREADY_RUNNING_REASON = "A tunnel is already running for this account"
