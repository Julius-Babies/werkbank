package app.dependencies.android_dns

import app.config.MainConfig
import app.data.Project
import app.data.extensions.project.getAllDomains
import app.dependencies.AppDependency
import app.dependencies.ReverseProxyRecord
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.dependencies.docker.NetworkConfig
import app.repository.ProjectRepository
import app.storage.isDevMode
import app.storage.storageRoot
import es.jvbabi.docker.kt.api.container.Container
import es.jvbabi.docker.kt.docker.DockerClient
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.getValue
import kotlin.uuid.Uuid

class Unbound : AppDependency, KoinComponent {
    private val dockerNetwork by inject<DockerNetwork>()
    private val mainConfig by inject<MainConfig>()
    private val dockerClient by inject<DockerClient>()

    private val name = buildString {
        append("werkbank-")
        if (isDevMode) append("dev-")
        append("unbound")
    }

    fun getContainer(): DockerContainer {
        return DockerContainer(
            image = "ghcr.io/zyrakq/unbound:latest",
            name = this.name,
            ports = listOf(
                Container.PortBinding(53, 53, Container.PortBinding.Protocol.UDP),
                Container.PortBinding(53, 53, Container.PortBinding.Protocol.TCP),
            ),
            volumes = mapOf(
                Container.VolumeBind.Host(unboundStorageRoot.absolutePath, readOnly = true) to "/etc/unbound/",
            ),
            environment = emptyMap(),
            networkConfigs = listOf(
                NetworkConfig(network = dockerNetwork)
            ),
            cmd = listOf("-d", "-vvv", "-c", "/etc/unbound/unbound.conf")
        )
    }

    val unboundStorageRoot by lazy {
        storageRoot
            .resolve("unbound")
            .apply { if (!exists()) mkdir() }
    }

    val configFile by lazy { unboundStorageRoot.resolve("unbound.conf") }

    private val projectRepository by inject<ProjectRepository>()

    override val key: String = "unbound"

    override suspend fun initialize() {
        val isEnabled = mainConfig.getConfig().androidDns.enabled
        if (!isEnabled) {
            println(buildStyledString {
                gray { +"Skipping Unbound as it is disabled" }
            })
            return
        }

        writeConfigFile()

        if (!hasAllRemoteControlKeys()) {
            val keyInitId = Uuid.random()
            val unboundTmpDir = File.getTempDirectory().resolve("unbound-key-init-$keyInitId").apply { mkdir(true) }

            val keyGenerator = DockerContainer(
                image = "ghcr.io/zyrakq/unbound:latest",
                volumes = mapOf(
                    Container.VolumeBind.Host(unboundTmpDir.absolutePath, readOnly = false) to "/etc/unbound/",
                ),
                entrypoint = "unbound-control-setup",
                cmd = listOf(""),
                name = "unbound-keygen-$keyInitId",
                ports = emptyList(),
                environment = emptyMap(),
                networkConfigs = listOf(),
            )
            keyGenerator.create()
            keyGenerator.start(false, false)
            keyGenerator.stop()
            keyGenerator.delete()
            unboundRemoteControlKeyFileNames.forEach { fileName ->
                val sourceFile = unboundTmpDir.resolve(fileName)
                val destination = unboundStorageRoot.resolve(fileName)
                sourceFile.copy(destination)
            }
        }

        if (getContainer().getState() == DockerContainer.State.NotExisting) {
            getContainer().create()
        }
    }

    override suspend fun start() {
        val isEnabled = mainConfig.getConfig().androidDns.enabled
        if (!isEnabled) {
            println(buildStyledString {
                gray { +"Skipping Unbound as it is disabled" }
            })
            return
        }
        val containerName = getContainer().name
        println(buildStyledString { green { +"Starting Unbound ($containerName)" } })
        getContainer().start(createIfNotExists = true)
    }

    override suspend fun stop() {
        val containerName = getContainer().name
        println(buildStyledString { blue { +"Stopping Unbound ($containerName)" } })
        getContainer().stop()
    }

    override fun isRequiredFor(project: Project): Boolean =
        project.getConfig().dependencies?.androidDns ?: false

    override fun isAlwaysRequired(): Boolean = false

    override val reverseProxyRecords: List<ReverseProxyRecord> = emptyList()
    override val webfacingDomains: List<String> = emptyList()

    fun writeConfigFile() {
        val domains = projectRepository
            .getAllProjects()
            .flatMap { it.getAllDomains() }
            .plus("traefik.werkbank.studio")
            .plus("pgadmin.werkbank.studio")
            .distinct()
        updateUnboundConfigIfNecessary(configFile, domains)
    }

    private val unboundRemoteControlKeyFileNames = listOf(
        "unbound_control.key",
        "unbound_control.pem",
        "unbound_server.key",
        "unbound_server.pem",
    )

    private fun hasAllRemoteControlKeys(): Boolean {
        return unboundRemoteControlKeyFileNames.all { unboundStorageRoot.resolve(it).exists() }
    }
}