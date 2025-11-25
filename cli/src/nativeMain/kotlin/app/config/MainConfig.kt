package app.config

import app.storage.storageRoot
import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class MainConfig {
    private val file = storageRoot.resolve("config.yaml")

    fun getConfig(): WerkbankConfig {
        if (!file.exists()) return WerkbankConfig()
        val content = file.readText()
        return Yaml.default.decodeFromString(WerkbankConfig.serializer(), content)
    }

    fun updateConfig(block: (config: WerkbankConfig) -> WerkbankConfig) {
        val config = block(getConfig())
        file.writeText(Yaml.default.encodeToString(WerkbankConfig.serializer(), config))
    }
}

@Serializable
data class WerkbankConfig(
    @SerialName("projects") val projects: List<Project>? = null
) {
    @Serializable
    data class Project(
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