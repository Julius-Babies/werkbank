package app.dependencies.docker

import app.dependencies.cli.runCommand
import es.jvbabi.docker.kt.docker.DockerClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DockerContainer(
    val image: String,
    val name: String,
    val ports: List<String>,
    val volumes: List<String>,
    val environment: Map<String, String>,
): KoinComponent {
    private val dockerClient by inject<DockerClient>()
    private val dockerNetwork by inject<DockerNetwork>()

    enum class State {
        Running, Stopped, NotExisting
    }

    fun getId(): String? {
        val psResponse = runCommand("docker", "ps", "-aqf", "name=$name")
        return psResponse.stdout!!.trim().ifBlank { null }
    }

    fun getState(): State {
        val id = getId() ?: return State.NotExisting
        val inspectResponse = runCommand("docker", "inspect", "-f", "{{.State.Running}}", id)
        return if (inspectResponse.stdout?.trim()?.toBoolean() == true) State.Running else State.Stopped
    }

    fun stop() {
        val state = getState()
        if (state != State.Running) return
        runCommand("docker", "stop", getId()!!)
    }

    fun start() {
        val state = getState()
        if (state == State.Running) return

        runCommand("docker", "start", getId()!!)
    }

    fun delete() {
        when (getState()) {
            State.Running -> { stop(); delete(); return }
            State.NotExisting -> return
            else -> Unit
        }

        runCommand("docker", "volume", "rm", getId()!!)
    }

    suspend fun create() {
        if (getState() != State.NotExisting) delete()
        if (dockerClient.images.getImages().none { it.repoTags.contains(this.image) }) dockerClient.pullImageWithLogs(this.image)
        dockerNetwork.initialize()
        runCommand(
            command = "docker",
            "create",
            "--name",
            this.name,
            "--network",
            dockerNetwork.name,
            "--label",
            "compose.project=werkbank",
            *ports.flatMap { listOf("-p", it) }.toTypedArray(),
            *volumes.flatMap { listOf("-v", it) }.toTypedArray(),
            *environment.flatMap { listOf("-e", "${it.key}=${it.value}") }.toTypedArray(),
            this.image
        )
    }
}
