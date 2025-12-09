package app.dependencies.rabbitmq

import app.data.Project
import app.data.extensions.project.usesRabbit
import app.dependencies.AppDependency
import app.dependencies.ReverseProxyRecord
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.hosts.HostsManager
import app.repository.ProjectRepository
import app.storage.isDevMode
import app.storage.storageRoot
import es.jvbabi.docker.kt.api.container.NetworkConfig
import es.jvbabi.docker.kt.api.container.PortBinding
import es.jvbabi.docker.kt.api.container.VolumeBind
import es.jvbabi.docker.kt.docker.DockerClient
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class RabbitMq : AppDependency, KoinComponent {
    override val key: String = "rabbitmq"

    private val dockerNetwork by inject<DockerNetwork>()
    private val dockerClient by inject<DockerClient>()
    private val projectRepository by inject<ProjectRepository>()
    private val hostsManager by inject<HostsManager>()

    private val rabbitRoot = storageRoot
        .resolve("rabbitmq")
        .resolve("data")

    val rabbitMqHostname = "rabbitmq.werkbank.studio"
    val rabbitMqContainer = DockerContainer(
        image = "rabbitmq:4-management-alpine",
        name = buildString {
            append("werkbank-")
            if (isDevMode) append("dev-")
            append("rabbitmq")
        },
        ports = listOf(
            PortBinding(5672, 5672, PortBinding.Protocol.TCP),
        ),
        volumes = mapOf(
            VolumeBind.Host(rabbitRoot.absolutePath) to "/var/lib/rabbitmq"
        ),
        environment = mapOf(
            "RABBITMQ_DEFAULT_USER" to "werkbank",
            "RABBITMQ_DEFAULT_PASS" to "werkbank"
        ),
        networkConfigs = listOf(
            NetworkConfig(
                networkId = runBlocking { dockerNetwork.getId()!! },
                aliases = listOf(rabbitMqHostname)
            )
        )
    )

    override suspend fun initialize() {
        if (!rabbitRoot.exists()) rabbitRoot.mkdir(recursive = true)
        hostsManager.addHost(rabbitMqHostname)
        if (rabbitMqContainer.getState() == DockerContainer.State.NotExisting) rabbitMqContainer.create()

        val projects = projectRepository
            .getAllProjects()
            .filter { it.usesRabbit() }
            .map { it.getConfig() }

        if (projects.isNotEmpty()) rabbitMqContainer.withRunning { rabbitMqContainer ->
            val existingVhostsResult = dockerClient.containers.runCommand(
                containerId = rabbitMqContainer.getId()!!,
                command = listOf(
                    "rabbitmqctl",
                    "list_vhosts"
                )
            )
            require(existingVhostsResult.exitCode == 0) { "Failed to list existing vhosts: ${existingVhostsResult.output}" }
            val existingVHosts = existingVhostsResult.output.lines()
                .drop(1) // "Listing vhosts ..."
                .drop(1) // name
                .map { it.trim().substringAfter("/") }
                .filter { it.isNotBlank() }

            val requiredVHosts = projects
                .flatMap { project ->
                    project.dependencies?.rabbitmq?.vhosts
                        .orEmpty()
                        .map { vhost ->
                            if (vhost.startsWith("${project.project.id}_")) vhost
                            else "${project.project.id}_$vhost"
                        }
                }
                .toSet()

            val missingVhosts = requiredVHosts - existingVHosts
            missingVhosts.forEach { missingVhost ->
                val createResult = dockerClient.containers.runCommand(
                    containerId = rabbitMqContainer.getId()!!,
                    command = listOf("rabbitmqctl", "add_vhost", missingVhost)
                )
                require(createResult.exitCode == 0) { "Failed to create vhost $missingVhost: ${createResult.output}" }
            }
        }
    }

    override suspend fun start() {
        println(buildStyledString { green { +"Starting RabbitMQ (${rabbitMqContainer.name})" } })
        rabbitMqContainer.start(createIfNotExists = true)
    }

    override suspend fun stop() {
        println(buildStyledString { blue { +"Stopping RabbitMQ (${rabbitMqContainer.name})" } })
        rabbitMqContainer.stop()
    }

    override val webfacingDomains: List<String> = listOf(rabbitMqHostname)

    override val reverseProxyRecords: List<ReverseProxyRecord> = listOf(
        ReverseProxyRecord(
            domain = rabbitMqHostname,
            port = 15672,
            containerName = rabbitMqContainer.name
        )
    )

    override fun isAlwaysRequired(): Boolean = false

    override fun isRequiredFor(project: Project): Boolean {
        return project.usesRabbit()
    }
}