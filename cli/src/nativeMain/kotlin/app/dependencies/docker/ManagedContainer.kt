package app.dependencies.docker

import app.dependencies.openssl.OpensslHandler
import app.storage.isDevMode
import es.jvbabi.docker.kt.api.Container
import es.jvbabi.docker.kt.docker.DockerClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A container werkbank owns, described by the spec it is supposed to have.
 *
 * The library's [Container] is a handle to one concrete container: a draft has no id and cannot be
 * started, and the one the daemon hands back is a different object than the draft it was created
 * from. This class is the bit in between - it finds the container by name and recreates it when the
 * spec here no longer matches what is running, so callers can describe what they want instead of
 * tracking which of the two they hold.
 */
class ManagedContainer(
    val image: String,
    val name: String,
    val ports: List<Container.PortBinding>,
    volumes: List<Container.VolumeBind>,
    val healthcheck: Container.Healthcheck? = null,
    val environment: Map<String, String>,
    val networkConfigs: List<NetworkConfig>,
    val entrypoint: String? = null,
    val cmd: List<String>? = null,
) : KoinComponent {
    private val dockerClient by inject<DockerClient>()
    private val dockerNetwork by inject<DockerNetwork>()
    private val opensslHandler by inject<OpensslHandler>()

    /** Every werkbank container gets the local root CA, so it can trust the internal certificates. */
    val volumes = volumes + Container.VolumeBind.Host(
        path = opensslHandler.rootCaFile.absolutePath,
        containerPath = "/etc/ssl/certs/werkbank-root-ca.crt"
    )

    /**
     * The states callers care about. Docker's `created` and `paused` are folded into [Stopped]:
     * werkbank only ever asks whether it has to start something.
     */
    enum class State {
        Running, Stopped, NotExisting
    }

    /** The container the daemon currently has under [name], or null if there is none. */
    suspend fun live(): Container? = dockerClient.containers.getByName(name)

    suspend fun getId(): String? = live()?.id

    suspend fun getState(): State = when (live()?.state) {
        null, is Container.State.NonExisting -> State.NotExisting
        Container.State.Existing.Running -> State.Running
        Container.State.Existing.Created,
        Container.State.Existing.Paused,
        Container.State.Existing.Stopped -> State.Stopped
    }

    /**
     * Creates the container, pulling the image and creating the network first if either is missing.
     * Replaces whatever currently holds the name.
     */
    suspend fun create(): Container {
        // The name has to be free before the daemon will hand it out again.
        delete()
        if (dockerClient.images.getImages().none { image in it.repoTags }) dockerClient.pullImageWithLogs(image)
        dockerNetwork.initialize()
        return draft().also { it.create() }
    }

    suspend fun start(createIfNotExists: Boolean, rebuildIfNotMatching: Boolean = true) {
        if (rebuildIfNotMatching && needsRebuild()) delete()

        val existing = live()
        if (existing?.state == Container.State.Existing.Running) return
        val container = existing ?: if (createIfNotExists) create() else return

        container.start()
        trustRootCa(container)
    }

    /** Stops the container if it is up. Does nothing when it is already down or does not exist. */
    suspend fun stop() {
        val container = live() ?: return
        takeDown(container)
    }

    /** Removes the container, stopping it first if needed. Does nothing when there is none. */
    suspend fun delete() {
        val container = live() ?: return
        takeDown(container)
        container.remove()
    }

    /**
     * Runs [block] with the container up, leaving it in the state it was found in: one that was
     * already running stays up afterwards, one that had to be started is stopped again.
     *
     * @param requireHealthy waits for [isHealthy] before running [block], for up to ten seconds
     */
    suspend fun withRunning(
        requireHealthy: Boolean = false,
        block: suspend (container: ManagedContainer) -> Unit
    ) {
        val wasRunning = getState() == State.Running
        if (!wasRunning) start(createIfNotExists = true)
        if (requireHealthy) withTimeoutOrNull(10.seconds) {
            while (!isHealthy()) delay(50.milliseconds)
        }

        block(this)
        if (!wasRunning) stop()
    }

    /**
     * Whether the declared healthcheck passes right now, by running its own test command.
     *
     * Docker tracks this itself, but the library does not hand the status out, so the test is run
     * here rather than read off the container. That skips Docker's start period and retry counting:
     * this is the check as of this instant, not the state machine Docker builds from it.
     *
     * A container without a healthcheck counts as healthy - there is nothing to wait for.
     */
    suspend fun isHealthy(): Boolean {
        val test = healthcheck?.test ?: return true
        val container = live() ?: return false
        val command = when (test.firstOrNull()) {
            "CMD-SHELL" -> listOf("sh", "-c", test.drop(1).joinToString(" "))
            "CMD" -> test.drop(1)
            else -> test
        }

        return dockerClient.containers.runCommand(containerId = container.id, command = command).exitCode == 0
    }

    /**
     * Whether the running container no longer matches this spec.
     *
     * The spec is hashed into a label at creation time, so a mismatch means the code now describes
     * a different container. Network aliases are compared on top: they are attached per endpoint
     * rather than baked into the container's config.
     */
    suspend fun needsRebuild(): Boolean {
        val existing = live() ?: return true
        if (existing.labels[SPEC_LABEL] != specSignature()) return true

        networkConfigs.forEach { config ->
            val attached = existing.networks.firstOrNull { it.network.name == config.network.name } ?: return true
            if (config.aliases.any { alias -> alias !in attached.aliases }) return true
        }

        return false
    }

    /** True when the tag this container was created from now resolves to a different image. */
    suspend fun imageChanged(): Boolean {
        val createdFrom = live()?.labels?.get(IMAGE_ID_LABEL)?.takeIf { it.isNotEmpty() } ?: return false
        val current = resolvedImageId() ?: return false
        return createdFrom != current
    }

    /**
     * Re-pulls the image and removes the container if its spec drifted or the image behind the tag
     * changed. Leaves it deleted for the caller's `provision()`/`start()` to recreate.
     */
    suspend fun update() {
        dockerClient.pullImageWithLogs(image)
        if (live() != null && (needsRebuild() || imageChanged())) delete()
    }

    /** The draft to create, with the werkbank-owned labels and the collected spec applied. */
    private suspend fun draft(): Container {
        val imageId = resolvedImageId()

        return dockerClient.containerBuilder(image) {
            name = this@ManagedContainer.name
            healthCheck = this@ManagedContainer.healthcheck
            entrypoint = this@ManagedContainer.entrypoint?.let { listOf(it) }
            cmd = this@ManagedContainer.cmd

            environment { putAll(this@ManagedContainer.environment) }

            labels {
                put("com.docker.compose.project", "werkbank" + if (isDevMode) "-dev" else "")
                put(SPEC_LABEL, specSignature())
                put(IMAGE_ID_LABEL, imageId.orEmpty())
            }

            volumes {
                this@ManagedContainer.volumes.forEach { volume ->
                    when (volume) {
                        is Container.VolumeBind.Host ->
                            bindHost(volume.path, volume.containerPath, volume.readOnly)
                        is Container.VolumeBind.Volume ->
                            bindVolume(volume.name, volume.containerPath, volume.readOnly)
                    }
                }
            }

            ports {
                // Each binding already names its own protocol, so it is bound on that one alone.
                this@ManagedContainer.ports.forEach { port ->
                    bind(port.containerPort, port.hostPort, setOf(port.protocol))
                }
            }

            networks {
                this@ManagedContainer.networkConfigs.forEach { config ->
                    connect(config.network.network, config.aliases)
                }
            }
        }
    }

    /** Stops [container] if it is up, so it can be removed or left in a known state. */
    private suspend fun takeDown(container: Container) {
        val isUp = container.state == Container.State.Existing.Running ||
            container.state == Container.State.Existing.Paused
        if (isUp) container.stop()
    }

    /** Makes the container pick up the root CA that is mounted into it. */
    private suspend fun trustRootCa(container: Container) {
        dockerClient.containers.runCommand(
            containerId = container.id,
            command = listOf("sh", "-lc", "if command -v update-ca-certificates >/dev/null 2>&1; then update-ca-certificates; fi")
        )
    }

    private suspend fun resolvedImageId(): String? =
        dockerClient.images.getImages().firstOrNull { image in it.repoTags }?.id

    /** Deterministic fingerprint of the desired container spec (excluding labels). */
    private fun specSignature(): String {
        val canonical = buildString {
            appendLine("image=$image")
            appendLine("entrypoint=${entrypoint ?: ""}")
            appendLine("cmd=${cmd?.joinToString(" ") ?: ""}")
            appendLine("ports=" + ports.map { it.toString() }.sorted().joinToString(","))
            appendLine("volumes=" + volumes.map { it.toString() }.sorted().joinToString(","))
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

        /** The image id the container was created from, so a moved tag can be told apart from a matching spec. */
        const val IMAGE_ID_LABEL = "studio.werkbank.image-id"
    }
}

data class NetworkConfig(
    val network: DockerNetwork,
    val aliases: List<String> = emptyList()
)
