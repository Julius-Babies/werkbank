package app.dependencies.docker

import app.storage.isDevMode
import es.jvbabi.docker.kt.api.Network
import es.jvbabi.docker.kt.docker.DockerClient
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DockerNetwork: KoinComponent {
    private val dockerClient by inject<DockerClient>()

    val name = "werkbank" + if (isDevMode) "-dev" else ""

    var network: Network
        private set

    init {
        runBlocking {
            network = dockerClient.networks.getNetworks().firstOrNull { it.name == name } ?: dockerClient.networkBuilder(name) {
                attachable = true
                driver = Network.Driver.Bridge
                labels {
                    put("compose.project", "werkbank")
                }
            }
        }
    }

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
        this.network.create()
    }
}