package app.dependencies.docker

import app.dependencies.openssl.OpensslHandler
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
    volumes: Map<Container.VolumeBind, String>,
    val healthcheck: Container.Healthcheck? = null,
    val environment: Map<String, String>,
    val networkConfigs: List<NetworkConfig>,
    val entrypoint: String? = null,
    val cmd: List<String>? = null,
): KoinComponent {
    private val dockerClient by inject<DockerClient>()
    private val dockerNetwork by inject<DockerNetwork>()
    private val opensslHandler by inject<OpensslHandler>()

    val volumes = volumes + (Container.VolumeBind.Host(opensslHandler.rootCaFile.absolutePath) to "/etc/ssl/certs/werkbank-root-ca.crt")

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

    suspend fun start(createIfNotExists: Boolean, rebuildIfNotMatching: Boolean = true) {
        if (rebuildIfNotMatching && needsRebuild()) delete()
        val state = getState()
        if (state == State.Running) return
        if (state == State.NotExisting && createIfNotExists) create()

        dockerClient.containers.startContainer(getId()!!)

        this.withRunning {
            val id = getId() ?: return@withRunning
            dockerClient.containers.runCommand(
                containerId = id,
                command = listOf("sh", "-lc", "if command -v update-ca-certificates >/dev/null 2>&1; then update-ca-certificates; fi")
            )
        }
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
            entrypoint = this.entrypoint?.let { listOf(it) },
            cmd = this.cmd,
            ports = this.ports,
            labels = mapOf(
                "com.docker.compose.project" to buildString {
                    append("werkbank")
                    if (isDevMode) append("-dev")
                },
                SPEC_LABEL to specSignature(),
            ),
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

    suspend fun needsRebuild(): Boolean {
        val id = getId() ?: return true
        val inspect = dockerClient.containers.inspectContainer(id)

        // The desired container spec (image, env, ports, volumes, cmd, entrypoint,
        // healthcheck, network aliases) is hashed into a label at creation time.
        // A mismatch means the code now describes a different container.
        if (inspect.config.labels[SPEC_LABEL] != specSignature()) return true

        this.networkConfigs.forEach { networkConfig ->
            val networkId = dockerClient.networks.getNetworks().firstOrNull { it.name == networkConfig.network.name }?.id ?: return true
            val existingNetwork = inspect.networkSettings.networks[networkId] ?: return true
            networkConfig.aliases.forEach { requiredAlias ->
                if (requiredAlias !in existingNetwork.aliases.orEmpty()) return true
            }
        }

        return false
    }

    /** True when the running container was created from a different image id than the one currently tagged locally. */
    suspend fun imageChanged(): Boolean {
        val id = getId() ?: return false
        val currentImageId = dockerClient.containers.getContainers(all = true).firstOrNull { it.id == id }?.imageId ?: return false
        val desiredImageId = dockerClient.images.getImages().firstOrNull { it.repoTags.contains(this.image) }?.id ?: return false
        return currentImageId != desiredImageId
    }

    /**
     * Re-pulls the image and recreates the container if its spec drifted or the
     * image behind the tag changed. Leaves the container deleted for the caller's
     * `provision()`/`start()` to recreate.
     */
    suspend fun update() {
        dockerClient.pullImageWithLogs(this.image)
        if (getState() != State.NotExisting && (needsRebuild() || imageChanged())) delete()
    }

    /** Deterministic fingerprint of the desired container spec (excluding labels). */
    private fun specSignature(): String {
        val canonical = buildString {
            appendLine("image=$image")
            appendLine("entrypoint=${entrypoint ?: ""}")
            appendLine("cmd=${cmd?.joinToString(" ") ?: ""}")
            appendLine("ports=" + ports.map { it.toString() }.sorted().joinToString(","))
            appendLine("volumes=" + volumes.entries.map { "${it.key}=>${it.value}" }.sorted().joinToString(","))
            appendLine("env=" + environment.entries.map { "${it.key}=${it.value}" }.sorted().joinToString(","))
            appendLine("healthcheck=${healthcheck?.toString() ?: ""}")
            appendLine("networks=" + networkConfigs.map { "${it.network.name}:${it.aliases.sorted().joinToString("|")}" }.sorted().joinToString(","))
        }

        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (byte in canonical.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L // FNV prime
        }
        return hash.toULong().toString(16)
    }

    companion object {
        const val SPEC_LABEL = "studio.werkbank.spec"
    }
}


data class NetworkConfig(
    val network: DockerNetwork,
    val aliases: List<String> = emptyList()
)