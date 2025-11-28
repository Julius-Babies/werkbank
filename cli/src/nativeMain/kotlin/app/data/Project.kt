package app.data

import app.config.MainConfig
import app.config.WerkbankConfig
import app.dependencies.docker.DockerContainer
import app.dependencies.openssl.OpensslHandler
import app.dependencies.reverse_proxy.TraefikManager
import app.hosts.HostsManager
import app.storage.isDevMode
import app.storage.storageRoot
import com.charleskorn.kaml.Yaml
import commands.setup.Werkbankfile
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.getValue
import kotlin.test.assertTrue

/**
 * @param path The path to the project directory containing the Werkbankfile.yaml
 */
data class Project(
    val id: String,
    val name: String,
    val path: String
): KoinComponent {
    private val hostsManager by inject<HostsManager>()
    private val opensslHandler by inject<OpensslHandler>()
    private val traefikManager by inject<TraefikManager>()
    private val mainConfig by inject<MainConfig>()

    val getProjectStorage by lazy {
        storageRoot
            .resolve("projects")
            .resolve(id)
            .apply { mkdir(recursive = true) }
    }

    private val configFile = File(path).resolve("Werkbankfile.yaml")

    fun getConfig(): Werkbankfile {
        val data = configFile.readText()
        val config = Yaml.default.decodeFromString(Werkbankfile.serializer(), data)
        return config
    }

    fun getWerkbankConfig(): WerkbankConfig.Project {
        mainConfig.getConfig().projects.orEmpty().firstOrNull { it.id == id }?.let { return it }
        error("Project with id $id not found in config")
    }

    fun updateHosts() {
        val domain = id.lowercase() + ".werkbank.local"
        hostsManager.addHost(domain)
    }

    suspend fun updateCertificates() {
        assertTrue(opensslHandler.isOpensslAvailable.await())
        val certificateFile = getProjectStorage.resolve("certificate.pem")
        val privateKeyFile = getProjectStorage.resolve("private.key")
        val services = getConfig().services
        // Regenerate certificates
        opensslHandler.createCertificatePair(
            certificateFile = certificateFile,
            privateKeyFile = privateKeyFile,
            mainDomain = id.lowercase() + ".werkbank.local",
            altDomains = services
                .flatMap { it.domains }
                .distinct()
                .map { "${it.lowercase()}.${id.lowercase()}.werkbank.local" }
        )
    }

    suspend fun setupProxy() {
        if (getConfig().services.isEmpty()) return
        traefikManager.initialize()
    }

    fun getContainers(): List<ProjectContainer> {
        return getConfig().containers.map { container ->
            ProjectContainer(
                name = container.name,
                container = DockerContainer(
                    image = container.image,
                    name = "werkbank${if (isDevMode) "-dev" else ""}-${this.id}-${container.name}",
                    ports = container.ports,
                    volumes = container.volumes,
                    environment = container.environment
                )
            )
        }
    }

    suspend fun start() {
        val services = getConfig().services
        getContainers().forEach { container ->
            val service = services.firstOrNull { service -> service.modes.docker?.container == container.name }
            if (service == null) {
                if (container.container.getState() == DockerContainer.State.NotExisting) {
                    println(buildStyledString { green { +"Creating container ${container.name} (${container.container.name})" } })
                    container.container.create()
                }
                println(buildStyledString { green { +"Starting container ${container.name} (${container.container.name})" } })
                container.container.start()
                return@forEach
            }

            val mode = mainConfig.getConfig()
                .projects.orEmpty()
                .first { project -> project.name == this.name }
                .services
                .first { service -> service.name == service.name }
                .serviceState
            if (mode == WerkbankConfig.Project.Service.ServiceState.Docker) {
                println(buildStyledString { green { +"Starting container ${container.name} (${container.container.name})" } })
                container.container.start()
            } else {
                println(buildStyledString { blue { +"Stopping container ${container.name} (${container.container.name})" } })
                container.container.stop()
            }
        }
    }
}

data class ProjectContainer(
    val name: String,
    val container: DockerContainer
)