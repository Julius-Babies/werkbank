package app.data

import app.config.MainConfig
import app.config.WerkbankConfig
import app.dependencies.docker.ManagedContainer
import app.dependencies.docker.DockerNetwork
import app.dependencies.docker.NetworkConfig
import app.dependencies.openssl.OpensslHandler
import app.dependencies.reverse_proxy.ReverseProxy
import app.hosts.HostsManager
import app.storage.isDevMode
import app.storage.storageRoot
import app.werkbank.shared.Werkbankfile
import com.charleskorn.kaml.Yaml
import es.jvbabi.docker.kt.api.Container
import es.jvbabi.docker.kt.docker.DockerClient
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.system.exitProcess
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
    private val reverseProxy by inject<ReverseProxy>()
    private val mainConfig by inject<MainConfig>()
    private val dockerNetwork by inject<DockerNetwork>()
    private val dockerClient by inject<DockerClient>()

    val getProjectStorage by lazy {
        storageRoot
            .resolve("projects")
            .resolve(id)
            .apply { mkdir(recursive = true) }
    }

    private val configFile = File(path).resolve("Werkbankfile.yaml")

    fun getConfig(): Werkbankfile {
        val data = configFile.readText()
        val config = try {
            Yaml.default.decodeFromString(Werkbankfile.serializer(), data)
        } catch (e: Exception) {
            println(buildStyledString { red { +"Error parsing ${configFile.absolutePath}: ${e.message}" } })
            exitProcess(1)
        }
        return config
    }

    fun getWerkbankConfig(): WerkbankConfig.Project {
        mainConfig.getConfig().projects.orEmpty().firstOrNull { it.id == id }?.let { return it }
        error("Project with id $id not found in config")
    }

    fun updateHosts() {
        val domain = id.lowercase() + ".werkbank.space"
        hostsManager.addHost(domain)
        getConfig().http
            .flatMap { it.domains.orEmpty() }
            .filterNot { it.isBlank() }
            .distinct()
            .map { if (it.endsWith(".$domain")) it else "$it.$domain" }
            .forEach { hostsManager.addHost(it) }
    }

    suspend fun updateCertificates() {
        assertTrue(opensslHandler.isOpensslAvailable.await())
        val certificateFile = getProjectStorage.resolve("certificate.pem")
        val privateKeyFile = getProjectStorage.resolve("private.key")
        val mainDomain = id.lowercase() + ".werkbank.space"
        // Regenerate certificates
        opensslHandler.createCertificatePair(
            certificateFile = certificateFile,
            privateKeyFile = privateKeyFile,
            mainDomain = id.lowercase() + ".werkbank.space",
            altDomains = getConfig().http
                .flatMap { it.domains.orEmpty() }
                .filterNot { it.isBlank() }
                .distinct()
                .map { if (it.endsWith(".$mainDomain")) it else "$it.$mainDomain" }
        )
    }

    suspend fun setupProxy() {
        if (getConfig().services.isEmpty()) return
        reverseProxy.configure()
        reverseProxy.provision()
    }

    /**
     * A volume source is treated as a named Docker volume when it is not a path,
     * i.e. it is not absolute and does not start with a relative path marker or contain a separator
     * ("database" -> named volume, "./database" / "/database" -> bind mount).
     */
    private fun isNamedVolumeSource(source: String): Boolean =
        !File.isPathAbsolute(source) && !source.startsWith(".") && !source.startsWith("~") && !source.contains("/")

    /**
     * Builds the name for a managed Docker volume, e.g. "werkbank-<project>-<name>"
     * (with a "dev-" marker in dev mode).
     */
    private fun namedVolumeName(source: String): String = buildString {
        append("werkbank-")
        if (isDevMode) append("dev-")
        append(this@Project.id)
        append("-")
        append(source)
    }

    fun getContainers(): List<ProjectContainer> {
        val config = getConfig()
        return config.containers.map { container ->
            ProjectContainer(
                name = container.name,
                container = ManagedContainer(
                    image = container.image,
                    name = "werkbank${if (isDevMode) "-dev" else ""}-${this.id}-${container.name}",
                    ports = container.ports.map { Container.PortBinding.from(it) },
                    volumes = container.volumes
                        .map { spec ->
                            val bind = Container.VolumeBind.from(spec)
                            // The library classifies the source itself, but werkbank's rule for what
                            // counts as a managed volume is the stricter one, so it decides here.
                            val source = when (bind) {
                                is Container.VolumeBind.Host -> bind.path
                                is Container.VolumeBind.Volume -> bind.name
                            }
                            when {
                                isNamedVolumeSource(source) -> Container.VolumeBind.Volume(
                                    name = namedVolumeName(source),
                                    containerPath = bind.containerPath,
                                    readOnly = bind.readOnly
                                )
                                File.isPathAbsolute(source) -> Container.VolumeBind.Host(
                                    path = source,
                                    containerPath = bind.containerPath,
                                    readOnly = bind.readOnly
                                )
                                else -> Container.VolumeBind.Host(
                                    path = File(this.path).resolve(source).absolutePath,
                                    containerPath = bind.containerPath,
                                    readOnly = bind.readOnly
                                )
                            }
                        }
                        .plus(Container.VolumeBind.Host(opensslHandler.keyStoreFile.absolutePath, "/ssl/keystore.jks", readOnly = true)),
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

    /**
     * Attaches the containers of services in `docker-dev` mode to the werkbank network.
     *
     * Those containers are not werkbank's - they come out of the project's own compose setup - so the
     * reverse proxy, which addresses them by name, can only reach them once they share a network with
     * it. The attachment lives on the container, not on the compose file, so it is gone as soon as
     * the container is recreated (`docker compose down` and up again). It is therefore re-established
     * here on every run rather than assumed to still be in place.
     *
     * A container that is not up yet is reported and skipped: the project's own stack is started
     * outside werkbank, so it not being there is a normal state, not a failure.
     */
    suspend fun attachDockerDevContainers() {
        val config = getConfig()
        val dockerDevServices = getWerkbankConfig().services
            .filter { it.serviceState == WerkbankConfig.Project.Service.ServiceState.DockerDev }
        if (dockerDevServices.isEmpty()) return

        dockerNetwork.initialize()
        dockerDevServices.forEach { service ->
            val containerName = config.services
                .firstOrNull { it.name == service.name }
                ?.modes?.dockerDev?.container
                ?: return@forEach

            val container = dockerClient.containers.getByName(containerName)
            if (container == null) {
                println(buildStyledString { yellow { +"Container $containerName for service ${service.name} is not running, skipping network attachment" } })
                return@forEach
            }

            // Docker refuses a second endpoint under the same name, so an attachment that is already
            // there has to be left alone rather than repeated.
            if (container.networks.any { it.network.name == dockerNetwork.name }) return@forEach

            container.connectTo(dockerNetwork.network)
            println(buildStyledString { green { +"Attached $containerName to ${dockerNetwork.name}" } })
        }
    }

    suspend fun start() {
        val services = getConfig().services
        getContainers().forEach { container ->
            val service = services.firstOrNull { service -> service.modes.docker?.container == container.name }
            if (service == null) {
                if (container.container.getState() == ManagedContainer.State.NotExisting) {
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
                    ManagedContainer.State.NotExisting -> container.container.create()
                    ManagedContainer.State.Running -> container.container.stop()
                    else -> Unit
                }
                container?.container?.start(createIfNotExists = true)
            }
            WerkbankConfig.Project.Service.ServiceState.DockerDev -> {
                if (getConfig().services.first { it.name == serviceName }.modes.dockerDev == null) {
                    error("Service $serviceName does not support Docker Dev mode")
                }
                container?.container?.stop()
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
        // Reads the state that was just written, so switching a service to docker-dev attaches its
        // container right away instead of waiting for the next `wb up`.
        attachDockerDevContainers()
        reverseProxy.configure()
    }

    suspend fun stop() {
        getContainers().forEach { container ->
            if (container.container.getState() == ManagedContainer.State.Running) {
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
    val container: ManagedContainer
) {
    enum class Type {
        Service, Dependency
    }
}