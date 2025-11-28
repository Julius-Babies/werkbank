package app.dependencies.reverse_proxy

import app.config.WerkbankConfig
import app.dependencies.docker.pullImageWithLogs
import app.dependencies.openssl.OpensslHandler
import app.hosts.HostsManager
import app.repository.ProjectRepository
import app.storage.storageRoot
import com.charleskorn.kaml.Yaml
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import es.jvbabi.docker.kt.docker.DockerClient
import es.jvbabi.docker.kt.docker.getSocketPath
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import platform.zlib.inflateGetHeader
import util.throwError

class TraefikManager : KoinComponent {

    val traefikImage = "traefik:v3.6.1"

    val traefikFileStorage by lazy { storageRoot.resolve("traefik").apply { if (!exists()) mkdir() } }
    val hostsManager by inject<HostsManager>()
    val dynamicConfigFolder by lazy { traefikFileStorage.resolve("dynamic").apply { if (!exists()) mkdir() } }
    val dashboardCertificatesFolder by lazy { traefikFileStorage.resolve("dashboard-certificates").apply { if (!exists()) mkdir() } }

    private val dockerClient by inject<DockerClient>()
    private val projectRepository by inject<ProjectRepository>()
    private val opensslHandler by inject<OpensslHandler>()

    suspend fun initialize() {
        if (dockerClient.images.getImages().none { it.repoTags.contains(traefikImage) }) pullImage()
        generateTraefikConfig()
        generateProxyConfig()
        createDashboardService()
        generateSslConfig()
    }

    private fun createContainer() {
        val createResponse = Command("docker")
            .args(
                "create",
                "--name",
                "werkbank-traefik",
                "--label",
                "compose.project=werkbank",
                "-p",
                "80:80",
                "-p",
                "443:443",
                "-v",
                dashboardCertificatesFolder.absolutePath + ":/ssl/dashboard:ro",
                "-v",
                storageRoot.resolve("projects").absolutePath + ":/projects",
                "-v",
                traefikFileStorage.absolutePath + ":/etc/traefik:ro",
                "-v",
                getSocketPath() + ":/var/run/docker.sock",
                traefikImage
            )
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()
        if (createResponse.status != 0) createResponse.throwError("Failed to create container")
    }

    private fun getContainerId(): String? {
        val psResponse = Command("docker")
            .args("ps", "-aqf", "name=werkbank-traefik")
            .stdout(Stdio.Null)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()
        if (psResponse.status != 0) psResponse.throwError("Failed to get container id")
        return psResponse.stdout?.trim()?.ifBlank { null }
    }

    private fun stopContainer() {
        val containerId = getContainerId() ?: return
        val stopResponse = Command("docker")
            .args("stop", containerId)
            .stdout(Stdio.Null)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()
        if (stopResponse.status != 0) stopResponse.throwError("Failed to stop container")
    }

    private fun startContainer() {
        val containerId = getContainerId() ?: return
        val startResponse = Command("docker")
            .args("start", containerId)
            .stdout(Stdio.Null)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()
        if (startResponse.status != 0) throw RuntimeException("Failed to start container, status ${startResponse.status}")
    }

    private fun deleteContainer() {
        val containerId = getContainerId() ?: return
        val rmResponse = Command("docker")
            .args("rm", containerId)
            .stdout(Stdio.Null)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()
        if (rmResponse.status != 0) throw RuntimeException("Failed to remove container, status ${rmResponse.status}")
    }

    private suspend fun pullImage() {
        dockerClient.pullImageWithLogs(traefikImage)
    }

    fun generateTraefikConfig() {
        val traefikConfigFile = traefikFileStorage.resolve("traefik.yaml")
        traefikConfigFile.writeText("""
            entryPoints:
              web:
                address: ":80"
              websecure:
                address: ":443"
            
            api:
              dashboard: true
              insecure: true
              disableDashboardAd: true
            
            providers:
              file:
                directory: /etc/traefik/dynamic
                watch: true
              docker:
                watch: true
                endpoint: "unix:///var/run/docker.sock"
                exposedByDefault: false
            
            log:
              level: DEBUG
        """.trimIndent())
    }

    /**
     * Creates the single SSL file for Traefik, giving it all paths to certificates it can use for services.
     */
    fun generateSslConfig() {
        val config = TraefikTlsConfig(
            tls = TraefikTlsConfig.Tls(
                certificates = listOf(
                    TraefikTlsConfig.Tls.Certificate(
                        certFile = "/ssl/dashboard/certificate.pem",
                        keyFile = "/ssl/dashboard/private.key"
                    )
                ) + projectRepository.getAllProjects().map { project ->
                    TraefikTlsConfig.Tls.Certificate(
                        certFile = "/projects/${project.id}/certificate.pem",
                        keyFile = "/projects/${project.id}/private.key"
                    )
                }
            )
        )
        val sslConfigFile = dynamicConfigFolder.resolve("ssl.yaml")
        sslConfigFile.writeText(Yaml.default.encodeToString(TraefikTlsConfig.serializer(), config))
    }

    /**
     * Generates the Traefik-Service configs for projects
     */
    fun generateProxyConfig() {
        val projects = projectRepository.getAllProjects().associate { it.getWerkbankConfig() to it.getConfig() }
        dynamicConfigFolder
            .listFiles()
            .filter { it.name.endsWith(".user.service.yaml") }
            .forEach { it.delete() }

        projects.forEach { (state, project) ->
            project.services.forEach { service ->
                val domains = service.domains
                    .map { "${it.lowercase()}.${project.project.id.lowercase()}.werkbank.local" }
                    .ifEmpty { listOf("${project.project.id.lowercase()}.werkbank.local") }
                    .distinct()
                val pathPrefixes = service.pathPrefixes.ifEmpty { listOf("/") }
                val serviceName = project.project.id.lowercase() + "-" + service.name.lowercase()
                println("$serviceName: ${domains.joinToString()} -> ${pathPrefixes.joinToString()}")

                val serviceFile = dynamicConfigFolder.resolve("${serviceName}.user.service.yaml")
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

    private fun createDashboardService() {
        hostsManager.addHost("traefik.werkbank.dev")
        val dashboardConfigFile = dynamicConfigFolder.resolve("dashboard.system.service.yaml")
        updateDashboardServiceIfNecessary(dashboardConfigFile)
        val dashboardCertificateFile = dashboardCertificatesFolder.resolve("certificate.pem")
        val dashboardPrivateKeyFile = dashboardCertificatesFolder.resolve("private.key")
        if (!dashboardCertificateFile.exists() || !dashboardPrivateKeyFile.exists()) {
            opensslHandler.createCertificatePair(
                certificateFile = dashboardCertificateFile,
                privateKeyFile = dashboardPrivateKeyFile,
                mainDomain = "traefik.werkbank.dev",
            )
        }
    }
}
