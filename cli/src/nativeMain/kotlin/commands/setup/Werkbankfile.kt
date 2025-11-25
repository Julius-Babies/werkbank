package commands.setup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Werkbankfile(
    @SerialName("project") val project: Project
) {
    @Serializable
    data class Project(
        @SerialName("name") val name: String,
        @SerialName("id") val id: String,
    )
}