package app.data

import app.config.WerkbankConfig
import app.dependencies.openssl.OpensslHandler
import app.hosts.HostsManager
import app.storage.storageRoot
import com.charleskorn.kaml.Yaml
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.test.assertTrue

data class Project(
    val id: String,
    val name: String,
    val path: String
): KoinComponent {
    private val hostsManager by inject<HostsManager>()
    private val opensslHandler by inject<OpensslHandler>()
    private val getProjectStorage by lazy {
        storageRoot
            .resolve("projects")
            .resolve(id)
            .apply { mkdir(recursive = true) }
    }

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

    suspend fun updateCertificates() {
        assertTrue(opensslHandler.isOpensslAvailable.await())
        val certificateFile = getProjectStorage.resolve("certificate.pem")
        val privateKeyFile = getProjectStorage.resolve("private.key")
        if (!certificateFile.exists() || !privateKeyFile.exists()) {
            // Regenerate certificates
            opensslHandler.createCertificatePair(certificateFile, privateKeyFile, id.lowercase() + ".werkbank.local")
        }
    }
}