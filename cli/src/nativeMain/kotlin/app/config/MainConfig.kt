package app.config

import app.storage.storageRoot
import com.charleskorn.kaml.Yaml

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
