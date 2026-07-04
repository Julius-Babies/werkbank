package app.werkbank.shared.cli.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UpdateResponse {
    @Serializable
    @SerialName("no_update")
    object NoUpdate : UpdateResponse()

    @Serializable
    @SerialName("update_available")
    data class UpdateAvailable(
        @SerialName("version") val version: String,
        @SerialName("download_url") val downloadUrl: String,
    ) : UpdateResponse()
}