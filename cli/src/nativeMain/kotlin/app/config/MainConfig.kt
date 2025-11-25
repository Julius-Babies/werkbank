package app.config

import app.storage.storageRoot
import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class MainConfig {
    private val file = storageRoot.resolve("config.yaml")

    private var currentConfig: WerkbankConfig? = null

    fun getConfig(): WerkbankConfig {
        if (currentConfig != null) return currentConfig!!
        if (!file.exists()) return WerkbankConfig()
        val content = file.readText()
        return Yaml.default.decodeFromString(WerkbankConfig.serializer(), content)
    }

    fun updateConfig(block: (config: WerkbankConfig) -> WerkbankConfig) {
        val config = block(getConfig())
        currentConfig = config
        file.writeText(Yaml.default.encodeToString(WerkbankConfig.serializer(), config))
    }
}

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