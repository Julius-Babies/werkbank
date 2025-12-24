package app.dependencies.android_dns

import app.data.extensions.project.getAllDomains
import app.data.Project
import app.dependencies.AppDependency
import app.dependencies.ReverseProxyRecord
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.dependencies.docker.NetworkConfig
import app.repository.ProjectRepository
import app.storage.isDevMode
import app.storage.storageRoot
import es.jvbabi.docker.kt.api.container.Container
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.getValue
import util.buildStyledString

class Unbound : AppDependency, KoinComponent {
    private val dockerNetwork by inject<DockerNetwork>()

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
                Container.VolumeBind.Host(configFile.absolutePath, readOnly = true) to "/etc/unbound/unbound.conf",
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
        writeConfigFile()
        if (getContainer().getState() == DockerContainer.State.NotExisting) getContainer().create()
    }

    override suspend fun start() {
        val containerName = getContainer().name
        println(buildStyledString { green { +"Starting Unbound ($containerName)" } })
        getContainer().start(createIfNotExists = true)
    }

    override suspend fun stop() {
        val containerName = getContainer().name
        println(buildStyledString { blue { +"Stopping Unbound ($containerName)" } })
        getContainer().stop()
    }

    override fun isRequiredFor(project: Project): Boolean = true
    override fun isAlwaysRequired(): Boolean = true

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
}