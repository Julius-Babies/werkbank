package app.werkbank.app.tunnel

import kotlin.uuid.Uuid

data class TunnelRequestRecord(
    val requestId: Uuid,
    val method: String,
    val uri: String,
    val projectId: String,
    val projectName: String,
    val serviceName: String?,
    val requestHeaders: Map<String, List<String>>,
    val responseHeaders: Map<String, List<String>>?,
    val statusCode: Int?,
    val error: String?,
    val startedAt: Long,
    val sentToTunnelAt: Long?,
    val responseStartedAt: Long?,
    val completedAt: Long?,
    val requestBodyPath: String?,
    val responseBodyPath: String?,
)
