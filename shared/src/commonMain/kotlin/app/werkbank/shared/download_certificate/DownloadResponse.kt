package app.werkbank.shared.download_certificate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DownloadResponse {
    @SerialName("error.not_found")
    @Serializable
    data object NotFound: DownloadResponse()

    @Serializable
    @SerialName("success")
    data class Success(
        @SerialName("certificate") val certificate: String,
        @SerialName("private_key") val privateKey: String,
    ) : DownloadResponse()
}