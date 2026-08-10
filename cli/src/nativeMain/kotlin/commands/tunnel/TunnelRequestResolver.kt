package commands.tunnel

import app.config.WerkbankConfig
import app.data.Project
import app.repository.ProjectRepository
import es.jvbabi.docker.kt.api.Container
import es.jvbabi.docker.kt.docker.DockerClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class TunnelRequestResolver: KoinComponent {

    private val projectRepository by inject<ProjectRepository>()
    private val dockerClient by inject<DockerClient>()

    data class Target(
        val url: String,
        val project: Project,
        val service: WerkbankConfig.Project.Service,
    )

    /**
     * Outcome of resolving a request to a local target. [Failed] carries a human-readable [reason] that
     * is surfaced to the user (as a tunnel checkpoint / on the proxy error page) instead of being
     * printed into the running TUI — the request must never just be dropped silently.
     */
    sealed interface Resolution {
        data class Resolved(val target: Target): Resolution
        data class Failed(val reason: String): Resolution
    }

    suspend fun getTarget(
        projectKey: String,
        serviceKey: String?,
        path: String,
        isWebsocket: Boolean,
    ): Resolution {
        val project = projectRepository.getById(projectKey)
            ?: return Resolution.Failed("Project $projectKey not found in config")

        val service = when (serviceKey) {
            null -> getTargetService(project, path)
                ?: return Resolution.Failed("No service found for path $path in project ${project.id}")
            else -> project.getWerkbankConfig().services.firstOrNull { service -> service.name == serviceKey }
                ?: return Resolution.Failed("Service $serviceKey not found in project $projectKey")
        }

        val targetUrl: String
        when (service.serviceState) {
            WerkbankConfig.Project.Service.ServiceState.Disabled -> {
                return Resolution.Failed("Service ${service.name} is disabled in project $projectKey")
            }
            WerkbankConfig.Project.Service.ServiceState.Local -> {
                val port = project.getConfig().services.first { it.name == service.name }.modes.local?.port
                    ?: return Resolution.Failed("Service ${service.name} has no local port")
                targetUrl = "127.0.0.1:$port${path}"
            }
            WerkbankConfig.Project.Service.ServiceState.Docker -> {
                val dockerConfig = project.getConfig().services.first { it.name == service.name }.modes.docker
                    ?: return Resolution.Failed("Service ${service.name} has no docker configuration")
                val container = project.getContainers().firstOrNull { it.name == dockerConfig.container }?.container
                    ?: return Resolution.Failed("Service ${service.name} has no docker container")

                targetUrl = container.live()?.address()?.let { "$it:${dockerConfig.port}$path" }
                    ?: return Resolution.Failed("Service ${service.name} is not running")
            }
            WerkbankConfig.Project.Service.ServiceState.DockerDev -> {
                val dockerConfig = project.getConfig().services.first { it.name == service.name }.modes.dockerDev
                    ?: return Resolution.Failed("Service ${service.name} has no docker-dev configuration")
                val container = dockerClient.containers.getByName(dockerConfig.container)
                    ?: return Resolution.Failed("Service ${service.name} has no docker dev container")

                targetUrl = container.address()?.let { "$it:${dockerConfig.port}$path" }
                    ?: return Resolution.Failed("Service ${service.name} is not running")
            }
        }

        return Resolution.Resolved(
            Target(
                url = buildString {
                    if (isWebsocket) append("ws://") else append("http://")
                    append(targetUrl)
                },
                project = project,
                service = service,
            )
        )
    }

    /**
     * The address to reach this container on, as the authority part of a URL.
     *
     * Docker only assigns endpoint addresses while a container runs, so this is null for one that is
     * merely created or was stopped again. IPv4 is preferred; an IPv6 address is bracketed, since it
     * goes into a URL where the colons would otherwise read as a port separator.
     */
    private fun Container.address(): String? =
        networks.firstNotNullOfOrNull { it.ipv4Address }
            ?: networks.firstNotNullOfOrNull { it.ipv6Address }?.let { "[$it]" }

    private fun getTargetService(
        project: Project,
        path: String
    ): WerkbankConfig.Project.Service? = project.getConfig().http.firstNotNullOfOrNull { rule ->
        if (rule.pathPrefixes.none { path.startsWith(it) }) return@firstNotNullOfOrNull null
        return@firstNotNullOfOrNull project.getWerkbankConfig().services.firstOrNull { it.name == rule.targetService }
    }
}