package app.data

import app.config.MainConfig
import app.config.WerkbankConfig
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.dependencies.docker.NetworkConfig
import app.dependencies.openssl.OpensslHandler
import app.dependencies.reverse_proxy.TraefikManager
import app.hosts.HostsManager
import app.storage.isDevMode
import app.storage.storageRoot
import com.charleskorn.kaml.Yaml
import commands.setup.Werkbankfile
import es.jvbabi.docker.kt.api.container.Container
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
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
    private val dockerNetwork by inject<DockerNetwork>()

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
        val domain = id.lowercase() + ".werkbank.space"
        hostsManager.addHost(domain)
        getConfig().services
            .flatMap { it.domains }
            .filterNot { it.isBlank() }
            .distinct()
            .map { if (it.endsWith(".$domain")) it else "$it.$domain" }
            .forEach { hostsManager.addHost(it) }
    }

    suspend fun updateCertificates() {
        assertTrue(opensslHandler.isOpensslAvailable.await())
        val certificateFile = getProjectStorage.resolve("certificate.pem")
        val privateKeyFile = getProjectStorage.resolve("private.key")
        val services = getConfig().services
        val mainDomain = id.lowercase() + ".werkbank.space"
        // Regenerate certificates
        opensslHandler.createCertificatePair(
            certificateFile = certificateFile,
            privateKeyFile = privateKeyFile,
            mainDomain = id.lowercase() + ".werkbank.space",
            altDomains = services
                .flatMap { it.domains }
                .filterNot { it.isBlank() }
                .distinct()
                .map { if (it.endsWith(".$mainDomain")) it else "$it.$mainDomain" }
        )
    }

    suspend fun setupProxy() {
        if (getConfig().services.isEmpty()) return
        traefikManager.initialize()
    }

    fun getContainers(): List<ProjectContainer> {
        val config = getConfig()
        return config.containers.map { container ->
            ProjectContainer(
                name = container.name,
                container = DockerContainer(
                    image = container.image,
                    name = "werkbank${if (isDevMode) "-dev" else ""}-${this.id}-${container.name}",
                    ports = container.ports.map { Container.PortBinding.from(it) },
                    volumes = container.volumes
                        .associate {
                            val bind = Container.VolumeBind.from(it)
                            val source = bind.first
                            if (source is Container.VolumeBind.Host) {
                                val path = source.path
                                if (File.isPathAbsolute(path)) return@associate bind
                                val absolutePath = File(this.path).resolve(path).absolutePath
                                Container.VolumeBind.Host(absolutePath, source.readOnly) to bind.second
                            } else bind
                        }
                        .plus(Container.VolumeBind.Host(opensslHandler.keyStoreFile.absolutePath, readOnly = true) to "/ssl/keystore.jks"),
                    environment = container.environment
                        .plus("KEYSTORE_PATH" to "/ssl/")
                        .plus("KEYSTORE_PASSWORD" to opensslHandler.keyStorePassword),
                    networkConfigs = listOf(
                        NetworkConfig(
                            network = dockerNetwork,
                            aliases = listOf(buildString {
                                append("werkbank-")
                                if (isDevMode) append("dev-")
                                append(this@Project.id)
                                append("-")
                                append(container.name)
                            })
                        )
                    ),
                ),
                type = if (container.type == Werkbankfile.Container.Type.Service) ProjectContainer.Type.Service else ProjectContainer.Type.Dependency
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
                container.container.start(createIfNotExists = true)
                return@forEach
            }

            val mode = mainConfig.getConfig()
                .projects.orEmpty()
                .first { project -> project.name == this.name }
                .services
                .first { service -> service.name == service.name }
                .serviceState
            if (mode == WerkbankConfig.Project.Service.ServiceState.Docker || container.type == ProjectContainer.Type.Dependency) {
                println(buildStyledString { green { +"Starting container ${container.name} (${container.container.name})" } })
                container.container.start(createIfNotExists = true)
            } else {
                println(buildStyledString { blue { +"Stopping container ${container.name} (${container.container.name})" } })
                container.container.stop()
            }
        }
    }

    suspend fun setServiceStateTo(serviceName: String, state: WerkbankConfig.Project.Service.ServiceState) {
        val container = getContainers().firstOrNull { it.name == serviceName }
        when (state) {
            WerkbankConfig.Project.Service.ServiceState.Disabled -> {
                container?.container?.stop()
            }
            WerkbankConfig.Project.Service.ServiceState.Docker -> {
                if (getConfig().services.first { it.name == serviceName }.modes.docker == null) {
                    error("Service $serviceName does not support Docker mode")
                }
                val currentContainerState = container?.container?.getState()
                when (currentContainerState) {
                    DockerContainer.State.NotExisting -> container.container.create()
                    DockerContainer.State.Running -> container.container.stop()
                    else -> Unit
                }
                container?.container?.start(createIfNotExists = true)
            }
            WerkbankConfig.Project.Service.ServiceState.Local -> {
                if (getConfig().services.first { it.name == serviceName }.modes.local == null) {
                    error("Service $serviceName does not support Local mode")
                }
                container?.container?.stop()
            }
        }
        mainConfig.updateConfig { config ->
            config.copy(
                projects = config.projects.orEmpty().map { project ->
                    if (project.name == this.name) project.copy(services = project.services.map { service ->
                        if (service.name == serviceName) service.copy(serviceState = state) else service
                    }) else project
                }
            )
        }
        traefikManager.generateProxyConfig()
    }

    suspend fun stop() {
        getContainers().forEach { container ->
            if (container.container.getState() == DockerContainer.State.Running) {
                println(buildStyledString { blue { +"Stopping container ${container.name} (${container.container.name})" } })
                container.container.stop()
                container.container.delete()
            }
        }
    }
}

data class ProjectContainer(
    val name: String,
    val type: Type,
    val container: DockerContainer
) {
    enum class Type {
        Service, Dependency
    }
}