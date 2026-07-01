package commands.tunnel

import app.config.WerkbankConfig
import app.data.Project
import app.repository.ProjectRepository
import es.jvbabi.docker.kt.docker.DockerClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class TunnelRequestResolver: KoinComponent {

    private val projectRepository by inject<ProjectRepository>()
    private val dockerClient by inject<DockerClient>()

    data class Target(
        val url: String,
        val project: Project,
        val service: WerkbankConfig.Project.Service,
    )

    suspend fun getTarget(
        projectKey: String,
        serviceKey: String?,
        path: String,
        isWebsocket: Boolean,
    ): Target? {
        val project = projectRepository.getById(projectKey)
        if (project == null) {
            println(buildStyledString {
                red { +"Project $projectKey not found in config" }
            })
            return null
        }

        val service = when (serviceKey) {
            null -> getTargetService(project, path)
            else -> {
                val requestedService = project.getWerkbankConfig().services.firstOrNull { service -> service.name == serviceKey }
                if (requestedService == null) {
                    println(buildStyledString {
                        red { +"Service $serviceKey not found in project $projectKey" }
                    })
                    return null
                }
                requestedService
            }
        } ?: return null

        val targetUrl: String
        when (service.serviceState) {
            WerkbankConfig.Project.Service.ServiceState.Disabled -> {
                println(buildStyledString {
                    red { +"Service ${service.name} is disabled in project $projectKey" }
                })
                return null
            }
            WerkbankConfig.Project.Service.ServiceState.Local -> {
                val port = project.getConfig().services.first { it.name == service.name }.modes.local?.port
                if (port == null) {
                    println(buildStyledString {
                        red { +"Service ${service.name} has no local port" }
                    })
                    return null
                }
                targetUrl = "127.0.0.1:$port${path}"
            }
            WerkbankConfig.Project.Service.ServiceState.Docker -> {
                val dockerConfig = project.getConfig().services.first { it.name == service.name }.modes.docker
                if (dockerConfig == null) {
                    println(buildStyledString {
                        red { +"Service ${service.name} has no docker configuration" }
                    })
                    return null
                }
                val container = project.getContainers().firstOrNull { it.name == dockerConfig.container }?.container
                if (container == null) {
                    println(buildStyledString {
                        red { +"Service ${service.name} has no docker container" }
                    })
                    return null
                }

                targetUrl = "${dockerClient.containers.inspectContainer(container.getId()!!).networkSettings.networks.values.first().ipAddress}:${dockerConfig.port}${path}"
            }
        }

        return Target(
            url = buildString {
                if (isWebsocket) append("ws://") else append("http://")
                append(targetUrl)
            },
            project = project,
            service = service,
        )
    }

    fun getTargetService(
        project: Project,
        path: String
    ): WerkbankConfig.Project.Service? {
        val requestedService = project.getConfig().http.firstNotNullOfOrNull { rule ->
            if (rule.pathPrefixes.none { path.startsWith(it) }) return@firstNotNullOfOrNull null
            return@firstNotNullOfOrNull project.getWerkbankConfig().services.firstOrNull { it.name == rule.targetService }
        }
        if (requestedService == null) {
            println(buildStyledString {
                red { +"No service found for path $path in project ${project.id}" }
            })
            return null
        }
        return requestedService
    }
}