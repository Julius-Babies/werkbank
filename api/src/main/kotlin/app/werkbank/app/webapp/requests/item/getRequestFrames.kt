package app.werkbank.app.webapp.requests.item

import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.app.tunnel.WsBridge
import app.werkbank.app.tunnel.WsFrameRecord
import app.werkbank.database.DatabaseManager
import app.werkbank.database.TunnelRequestFrames
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.ktor.ext.inject

fun Route.getRequestFrames() {
    val db by inject<DatabaseManager>()
    val tunnelManager by inject<TunnelManager>()

    authenticate(AUTH_USER_JWT) {
        get {
            val request = call.getRequestWithPrincipalAsOwner() ?: return@get
            val principal = call.principal<UserPrincipal>()!!

            // Prefer the live connection (frames not yet persisted) while it is still open.
            val live = tunnelManager.getTunnel(principal.user)
                ?.requests?.value
                ?.firstOrNull { it.requestId == request.id.value } as? WsBridge

            val frames = if (live != null) {
                live.framesSnapshot().map { it.toResponse() }
            } else {
                db.query {
                    TunnelRequestFrames
                        .selectAll()
                        .where { TunnelRequestFrames.request eq request.id }
                        .orderBy(TunnelRequestFrames.sequence, SortOrder.ASC)
                        .map {
                            FrameResponse(
                                sequence = it[TunnelRequestFrames.sequence],
                                direction = it[TunnelRequestFrames.direction],
                                opcode = it[TunnelRequestFrames.opcode],
                                text = it[TunnelRequestFrames.text],
                                binaryBase64 = it[TunnelRequestFrames.binaryBase64],
                                size = it[TunnelRequestFrames.size],
                                timestamp = it[TunnelRequestFrames.timestamp].toEpochMilliseconds(),
                                closeCode = it[TunnelRequestFrames.closeCode],
                                closeReason = it[TunnelRequestFrames.closeReason],
                            )
                        }
                }
            }

            call.respond(frames)
        }
    }
}

private fun WsFrameRecord.toResponse() = FrameResponse(
    sequence = sequence,
    direction = direction.name.lowercase(),
    opcode = opcode.name.lowercase(),
    text = text,
    binaryBase64 = binaryBase64,
    size = size,
    timestamp = timestamp,
    closeCode = closeCode,
    closeReason = closeReason,
)

@Serializable
data class FrameResponse(
    @SerialName("sequence") val sequence: Int,
    @SerialName("direction") val direction: String,
    @SerialName("opcode") val opcode: String,
    @SerialName("text") val text: String?,
    @SerialName("binary_base64") val binaryBase64: String?,
    @SerialName("size") val size: Int,
    @SerialName("timestamp") val timestamp: Long,
    @SerialName("close_code") val closeCode: Int?,
    @SerialName("close_reason") val closeReason: String?,
)
