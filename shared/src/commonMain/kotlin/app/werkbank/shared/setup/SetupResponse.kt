package app.werkbank.shared.setup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class SetupResponse(
    @SerialName("project_id") val projectId: Uuid,
)