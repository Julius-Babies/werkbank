package app.dependencies.docker

import app.storage.isDevMode
import es.jvbabi.docker.kt.api.container.Container
import es.jvbabi.docker.kt.api.container.ContainerState
import es.jvbabi.docker.kt.docker.DockerClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DockerContainer(
    val image: String,
    val name: String,
    val ports: List<Container.PortBinding>,
    val volumes: Map<Container.VolumeBind, String>,
    val healthcheck: Container.Healthcheck? = null,
    val environment: Map<String, String>,
    val networkConfigs: List<NetworkConfig>,
    val cmd: List<String>? = null,
): KoinComponent {
    private val dockerClient by inject<DockerClient>()
    private val dockerNetwork by inject<DockerNetwork>()

    enum class State {
        Running, Stopped, NotExisting
    }

    suspend fun getId(): String? {
        return dockerClient.containers
            .getContainers(all = true)
            .firstOrNull { it.names.contains("/$name") }
            ?.id
    }

    suspend fun getState(): State {
        val id = getId() ?: return State.NotExisting
        val container = dockerClient.containers.getContainers(all = true).firstOrNull { it.id == id } ?: return State.NotExisting
        return when (container.state) {
            ContainerState.DEAD -> State.Stopped
            ContainerState.PAUSED -> State.Stopped
            ContainerState.CREATED -> State.Stopped
            ContainerState.RUNNING -> State.Running
            ContainerState.REMOVING -> State.Stopped
            ContainerState.EXITED -> State.Stopped
            ContainerState.RESTARTING -> State.Running
        }
    }

    suspend fun stop() {
        val state = getState()
        if (state != State.Running) return
        dockerClient.containers.stopContainer(getId()!!)
    }

    suspend fun start(createIfNotExists: Boolean) {
        val state = getState()
        if (state == State.Running) return
        if (state == State.NotExisting && createIfNotExists) create()

        dockerClient.containers.startContainer(getId()!!)
    }

    suspend fun delete() {
        when (getState()) {
            State.Running -> { stop(); delete(); return }
            State.NotExisting -> return
            else -> Unit
        }

        dockerClient.containers.deleteContainer(getId()!!)
    }

    suspend fun create() {
        if (getState() != State.NotExisting) delete()
        if (dockerClient.images.getImages().none { it.repoTags.contains(this.image) }) dockerClient.pullImageWithLogs(this.image)
        dockerNetwork.initialize()
        dockerClient.containers.createContainer(
            image = this.image,
            name = this.name,
            volumeBinds = this.volumes,
            environment = this.environment,
            healthCheck = this.healthcheck,
            cmd = this.cmd,
            ports = this.ports,
            labels = mapOf("com.docker.compose.project" to buildString {
                append("werkbank")
                if (isDevMode) append("-dev")
            }),
            networkConfigs = this.networkConfigs.map {
                Container.NetworkConfig(
                    networkId = it.network.getId() ?: throw NetworkNotFoundException(it.network.name),
                    aliases = it.aliases
                )
            },
        )
    }

    suspend fun withRunning(
        requireHealthy: Boolean = false,
        block: suspend (container: DockerContainer) -> Unit
    ) {
        val isRunning = getState() == State.Running
        if (!isRunning) start(createIfNotExists = true)
        if (requireHealthy) coroutineScope {
            withTimeoutOrNull(10000) { while (!isHealthy()) delay(50) }
        }

        block(this)
        if (!isRunning) stop()
    }

    suspend fun isHealthy(): Boolean {
        val state = dockerClient.containers
            .inspectContainer(this.getId()!!)
            .state
            .health
            ?.status

        return state == "healthy"
    }
}


data class NetworkConfig(
    val network: DockerNetwork,
    val aliases: List<String> = emptyList()
)