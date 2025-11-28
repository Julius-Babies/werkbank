package app.dependencies.reverse_proxy

import app.config.WerkbankConfig
import app.dependencies.docker.pullImageWithLogs
import app.repository.ProjectRepository
import app.storage.storageRoot
import es.jvbabi.docker.kt.docker.DockerClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class TraefikManager : KoinComponent {

    val traefikImage = "traefik:v3.6.1"

    val traefikFileStorage by lazy { storageRoot.resolve("traefik").apply { if (!exists()) mkdir() } }
    val dynamicConfigFolder by lazy { traefikFileStorage.resolve("dynamic").apply { if (!exists()) mkdir() } }

    private val dockerClient by inject<DockerClient>()
    private val projectRepository by inject<ProjectRepository>()

    suspend fun initialize() {
        if (dockerClient.images.getImages().none { it.repoTags.contains(traefikImage) }) pullImage()
        generateProxyConfig()
    }

    private suspend fun pullImage() {
        dockerClient.pullImageWithLogs(traefikImage)
    }

    fun generateProxyConfig() {
        val projects = projectRepository.getAllProjects().associate { it.getWerkbankConfig() to it.getConfig() }
        dynamicConfigFolder.listFiles().forEach { it.delete() }

        projects.forEach { (state, project) ->
            project.services.forEach { service ->
                val domains = service.domains
                    .map { "${it.lowercase()}.${project.project.id.lowercase()}.werkbank.local" }
                    .ifEmpty { listOf("${project.project.id.lowercase()}.werkbank.local") }
                    .distinct()
                val pathPrefixes = service.pathPrefixes.ifEmpty { listOf("/") }
                val serviceName = project.project.id.lowercase() + "-" + service.name.lowercase()
                println("$serviceName: ${domains.joinToString()} -> ${pathPrefixes.joinToString()}")

                val serviceFile = dynamicConfigFolder.resolve("${serviceName}.yaml")
                val serviceState = state.services.first { it.name == service.name }.serviceState

                val url = when (serviceState) {
                    WerkbankConfig.Project.Service.ServiceState.Disabled -> return@forEach
                    WerkbankConfig.Project.Service.ServiceState.Docker -> {
                        val container = service.modes.docker!!.container
                        val port = service.modes.docker.port
                        "http://${container}-$serviceName:$port"
                    }
                    WerkbankConfig.Project.Service.ServiceState.Local -> "http://host.docker.internal:${service.modes.local!!.port}"
                }

                serviceFile.writeText(
                    """
                    http:
                      routers:
                        ${serviceName}-router:
                          rule: (${domains.joinToString(" || ") { "Host(`$it`)" }}) && (${pathPrefixes.joinToString(" || ") { "PathPrefix(`$it`)" }})
                          service: ${serviceName}-service
                          entryPoints:
                            - "websecure"
                          tls: {}
                      services:
                        ${serviceName}-service:
                          loadBalancer:
                            passHostHeader: true
                            servers:
                              - url: $url
                """.trimIndent()
                )
            }
        }
    }
}
