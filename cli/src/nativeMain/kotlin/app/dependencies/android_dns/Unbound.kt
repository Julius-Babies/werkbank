package app.dependencies.android_dns

import app.data.extensions.project.getAllDomains
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.repository.ProjectRepository
import app.storage.isDevMode
import app.storage.storageRoot
import es.jvbabi.docker.kt.api.container.NetworkConfig
import es.jvbabi.docker.kt.api.container.PortBinding
import es.jvbabi.docker.kt.api.container.VolumeBind
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.getValue

class Unbound : KoinComponent {
    private val dockerNetwork by inject<DockerNetwork>()

    private val name = buildString {
        append("werkbank-")
        if (isDevMode) append("dev-")
        append("unbound")
    }

    suspend fun getContainer(): DockerContainer {
        return DockerContainer(
            image = "ghcr.io/zyrakq/unbound:latest",
            name = this.name,
            ports = listOf(
                PortBinding(53, 53, PortBinding.Protocol.UDP),
                PortBinding(53, 53, PortBinding.Protocol.TCP),
            ),
            volumes = mapOf(
                VolumeBind.Host(configFile.absolutePath, readOnly = true) to "/etc/unbound/unbound.conf",
            ),
            environment = emptyMap(),
            networkConfigs = listOf(
                NetworkConfig(networkId = dockerNetwork.getId()!!)
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

    suspend fun initialize() {
        writeConfigFile()
        if (getContainer().getState() == DockerContainer.State.NotExisting) getContainer().create()
    }

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