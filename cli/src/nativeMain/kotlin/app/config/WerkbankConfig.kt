package app.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WerkbankConfig(
    @SerialName("projects") val projects: List<Project>? = null
) {
    @Serializable
    data class Project(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        @SerialName("path") val path: String,
        @SerialName("submodules") val submodules: List<Submodule>
    ) {
        @Serializable
        data class Submodule(
            @SerialName("name") val name: String,
            @SerialName("path") val path: String,
        )
    }
}