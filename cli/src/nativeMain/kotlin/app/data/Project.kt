package app.data

import app.config.WerkbankConfig
import app.hosts.HostsManager
import com.charleskorn.kaml.Yaml
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class Project(
    val id: String,
    val name: String,
    val path: String
): KoinComponent {
    private val hostsManager by inject<HostsManager>()

    fun getConfig(): WerkbankConfig {
        val file = File(path)
        val data = file.readText()
        val config = Yaml.default.decodeFromString(WerkbankConfig.serializer(), data)
        return config
    }

    fun updateHosts() {
        val domain = id.lowercase() + ".werkbank.local"
        hostsManager.addHost(domain)
    }
}