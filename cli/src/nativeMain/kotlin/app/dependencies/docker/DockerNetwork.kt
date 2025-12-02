package app.dependencies.docker

import app.storage.isDevMode
import es.jvbabi.docker.kt.api.network.NetworkDriver
import es.jvbabi.docker.kt.docker.DockerClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DockerNetwork: KoinComponent {
    private val dockerClient by inject<DockerClient>()

    val name = "werkbank" + if (isDevMode) "-dev" else ""

    suspend fun initialize() {
        if (getStatus() == Status.Missing) create()
    }

    enum class Status {
        Created, Missing
    }

    suspend fun getStatus(): Status {
        val networks = dockerClient.networks.getNetworks()
        if (networks.any { it.name == name }) return Status.Created

        return Status.Missing
    }

    suspend fun create() {
        dockerClient.networks.createNetwork(
            name = name,
            driver = NetworkDriver.Bridge,
            attachable = true,
            labels = mapOf("compose.project" to "werkbank")
        )
    }

    suspend fun getId(): String? {
        return dockerClient.networks.getNetworks().firstOrNull { it.name == name }?.id
    }
}