package app.dependencies.reverse_proxy

import app.config.WerkbankConfig
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.dependencies.openssl.OpensslHandler
import app.hosts.HostsManager
import app.repository.ProjectRepository
import app.storage.isDevMode
import app.storage.storageRoot
import com.charleskorn.kaml.Yaml
import es.jvbabi.docker.kt.api.container.NetworkConfig
import es.jvbabi.docker.kt.api.container.VolumeBind
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class TraefikManager : KoinComponent {

    val traefikImage = "traefik:v3.6.1"
    val name = buildString {
        append("werkbank-")
        if (isDevMode) append("dev-")
        append("traefik")
    }

    val traefikFileStorage by lazy { storageRoot.resolve("traefik").apply { if (!exists()) mkdir() } }
    private val hostsManager by inject<HostsManager>()
    private val dockerNetwork by inject<DockerNetwork>()
    val dynamicConfigFolder by lazy { traefikFileStorage.resolve("dynamic").apply { if (!exists()) mkdir() } }
    val dashboardCertificatesFolder by lazy { traefikFileStorage.resolve("dashboard-certificates").apply { if (!exists()) mkdir() } }

    private val projectRepository by inject<ProjectRepository>()
    private val opensslHandler by inject<OpensslHandler>()

    private var dockerContainer: DockerContainer? = null
    suspend fun getContainer(): DockerContainer {
        dockerNetwork.initialize()
        if (dockerContainer != null) return dockerContainer!!
        dockerContainer = DockerContainer(
            image = this.traefikImage,
            name = this.name,
            ports = listOf("80:80", "443:443"),
            volumes = mapOf(
                VolumeBind.Host(traefikFileStorage.absolutePath, readOnly = true) to "/etc/traefik",
                VolumeBind.Host(storageRoot.resolve("projects").absolutePath, readOnly = true) to "/projects",
                VolumeBind.Host(dashboardCertificatesFolder.absolutePath, readOnly = true) to "/ssl/dashboard",
                VolumeBind.Host("/var/run/docker.sock") to "/var/run/docker.sock"
            ),
            environment = emptyMap(),
            networkConfigs = listOf(
                NetworkConfig(networkId = dockerNetwork.getId()!!)
            ),
        )
        return dockerContainer!!
    }

    suspend fun initialize() {
        generateTraefikConfig()
        generateProxyConfig()
        createDashboardService()
        generateSslConfig()
        if (getContainer().getState() == DockerContainer.State.NotExisting) getContainer().create()
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
            val projectBaseDomain = "${project.project.id.lowercase()}.werkbank.space"
            project.services.forEach { service ->
                val domains = service.domains
                    .map {
                        if (it.isBlank()) projectBaseDomain
                        else "${it.lowercase()}.${project.project.id.lowercase()}.werkbank.space"
                    }
                    .ifEmpty { listOf(projectBaseDomain) }
                    .distinct()
                val pathPrefixes = service.pathPrefixes.ifEmpty { listOf("/") }
                val serviceName = project.project.id.lowercase() + "-" + service.name.lowercase()

                val serviceFile = dynamicConfigFolder.resolve("${serviceName}.user.service.yaml")
                val serviceState = state.services.firstOrNull { it.name == service.name }?.serviceState

                val url = when (serviceState) {
                    WerkbankConfig.Project.Service.ServiceState.Disabled, null -> return@forEach
                    WerkbankConfig.Project.Service.ServiceState.Docker -> {
                        val container = service.modes.docker!!.container
                        val port = service.modes.docker.port
                        val containerName = "werkbank${if (isDevMode) "-dev" else ""}-${project.project.id.lowercase()}-${container}"
                        "http://${containerName}:$port"
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
        hostsManager.addHost("traefik.werkbank.studio")
        val dashboardConfigFile = dynamicConfigFolder.resolve("dashboard.system.service.yaml")
        updateDashboardServiceIfNecessary(dashboardConfigFile)
        val dashboardCertificateFile = dashboardCertificatesFolder.resolve("certificate.pem")
        val dashboardPrivateKeyFile = dashboardCertificatesFolder.resolve("private.key")
        if (!dashboardCertificateFile.exists() || !dashboardPrivateKeyFile.exists()) {
            opensslHandler.createCertificatePair(
                certificateFile = dashboardCertificateFile,
                privateKeyFile = dashboardPrivateKeyFile,
                mainDomain = "traefik.werkbank.studio",
            )
        }
    }
}
