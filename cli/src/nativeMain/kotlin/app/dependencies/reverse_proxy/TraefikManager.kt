package app.dependencies.reverse_proxy

import app.config.WerkbankConfig
import app.data.Project
import app.dependencies.AppDependency
import app.dependencies.ReverseProxyRecord
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.dependencies.docker.NetworkConfig
import app.dependencies.openssl.OpensslHandler
import app.hosts.HostsManager
import app.repository.ProjectRepository
import app.storage.isDevMode
import app.storage.storageRoot
import com.charleskorn.kaml.Yaml
import es.jvbabi.docker.kt.api.container.Container
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.buildStyledString
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.distinct
import kotlin.collections.ifEmpty
import kotlin.text.isBlank
import kotlin.text.lowercase

class TraefikManager : AppDependency, KoinComponent {

    val traefikImage = "traefik:v3.6.1"
    val name = buildString {
        append("werkbank-")
        if (isDevMode) append("dev-")
        append("traefik")
    }

    val traefikDomain = "traefik.werkbank.studio"
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))
    val traefikFileStorage by lazy { storageRoot.resolve("traefik").apply { if (!exists()) mkdir() } }
    private val hostsManager by inject<HostsManager>()
    private val dockerNetwork by inject<DockerNetwork>()
    val dynamicConfigFolder by lazy { traefikFileStorage.resolve("dynamic").apply { if (!exists()) mkdir() } }

    private val projectRepository by inject<ProjectRepository>()
    private val opensslHandler by inject<OpensslHandler>()

    private var dockerContainer: DockerContainer? = null
    suspend fun getContainer(): DockerContainer {
        dockerNetwork.initialize()
        if (dockerContainer != null) return dockerContainer!!
        dockerContainer = DockerContainer(
            image = this.traefikImage,
            name = this.name,
            ports = listOf(
                Container.PortBinding(80, 80, Container.PortBinding.Protocol.TCP),
                Container.PortBinding(443, 443, Container.PortBinding.Protocol.TCP)
            ),
            volumes = mapOf(
                Container.VolumeBind.Host(traefikFileStorage.absolutePath, readOnly = true) to "/etc/traefik",
                Container.VolumeBind.Host(storageRoot.resolve("projects").absolutePath, readOnly = true) to "/projects",
                Container.VolumeBind.Host(opensslHandler.internalCertificateDirectory.absolutePath, readOnly = true) to "/ssl/internal",
                Container.VolumeBind.Host("/var/run/docker.sock") to "/var/run/docker.sock"
            ),
            environment = emptyMap(),
            networkConfigs = listOf(
                NetworkConfig(
                    network = dockerNetwork,
                    aliases = listOf(traefikDomain) + getAllDomainsFromProjects()
                )
            ),
        )
        return dockerContainer!!
    }

    override val key: String = "traefik"

    override suspend fun initialize() {
        opensslHandler.isOpensslAvailable.await().let { require(it) { "OpenSSL is not available"} }
        generateTraefikConfig()
        generateProxyConfig()
        createDashboardService()
        createInternalServices()
        generateSslConfig()
        if (getContainer().getState() == DockerContainer.State.NotExisting) getContainer().create()
    }

    override suspend fun start() {
        val containerName = getContainer().name
        println(buildStyledString { green { +"Starting Traefik ($containerName)" } })
        getContainer().start(createIfNotExists = true, rebuildIfNotMatching = false)
    }

    override suspend fun stop() {
        val containerName = getContainer().name
        println(buildStyledString { blue { +"Stopping Traefik ($containerName)" } })
        getContainer().stop()
    }

    private fun getAllDomainsFromProjects(): List<String> {
        val projects = projectRepository.getAllProjects().map { it.getConfig() }
        return projects.flatMap { project ->
            val projectBaseDomain = "${project.project.id.lowercase()}.werkbank.space"
            project.services.flatMap { service ->
                service.domains
                    .map {
                        if (it.isBlank()) projectBaseDomain
                        else "${it.lowercase()}.${project.project.id.lowercase()}.werkbank.space"
                    }
                    .ifEmpty { listOf(projectBaseDomain) }
                    .distinct()
            }
        }.distinct()
    }

    override fun isRequiredFor(project: Project): Boolean = true
    override fun isAlwaysRequired(): Boolean = true

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
                certificates = projectRepository.getAllProjects().map { project ->
                    TraefikTlsConfig.Tls.Certificate(
                        certFile = "/projects/${project.id}/certificate.pem",
                        keyFile = "/projects/${project.id}/private.key"
                    )
                } + dependencies.filter { it.webfacingDomains.isNotEmpty() }.map { dependency ->
                    TraefikTlsConfig.Tls.Certificate(
                        certFile = "/ssl/internal/${dependency.key}.crt",
                        keyFile = "/ssl/internal/${dependency.key}.key"
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

                val rule = "(${domains.joinToString(" || ") { "Host(`$it`)" }}) && (${pathPrefixes.joinToString(" || ") { "PathPrefix(`$it`)" }})"

                generateServiceConfig(
                    serviceFile = serviceFile,
                    serviceName = serviceName,
                    rule = rule,
                    url = url,
                    priority = service.routingPriority
                )
            }
        }
    }

    private fun createDashboardService() {
        hostsManager.addHost(traefikDomain)
        val dashboardConfigFile = dynamicConfigFolder.resolve("dashboard.system.service.yaml")
        updateDashboardServiceIfNecessary(dashboardConfigFile)
    }

    private fun createInternalServices() {
        dynamicConfigFolder.listFiles().filter { it.name.endsWith(".internal.service.yaml") }.forEach { it.delete() }
        dependencies
            .associate { it.key to it.reverseProxyRecords }
            .forEach { (dependencyKey, records) ->
                records.forEach { record ->
                    generateInternalServiceConfig(dependencyKey, record)
                }
            }
    }

    private fun generateInternalServiceConfig(dependencyKey: String, record: ReverseProxyRecord) {
        hostsManager.addHost(record.domain)

        val serviceName = "${dependencyKey}-${record.domain.replace(".", "-")}"
        val serviceFile = dynamicConfigFolder.resolve("${serviceName}.internal.service.yaml")
        val url = "http://${record.containerName}:${record.port}"
        val rule = "Host(`${record.domain}`)"

        generateServiceConfig(
            serviceFile = serviceFile,
            serviceName = serviceName,
            rule = rule,
            url = url,
            priority = null
        )
    }

    private fun generateServiceConfig(
        serviceFile: File,
        serviceName: String,
        rule: String,
        url: String,
        priority: Int?
    ) {
        val serviceConfig = buildString {
            appendLine("http:")
            appendLine("  routers:")
            appendLine("    ${serviceName}-router:")
            appendLine("      rule: $rule")
            appendLine("      service: ${serviceName}-service")
            appendLine("      entryPoints: [\"websecure\"]")
            appendLine("      tls: {}")
            if (priority != null) appendLine("      priority: $priority")
            appendLine("  services:")
            appendLine("    ${serviceName}-service:")
            appendLine("      loadBalancer:")
            appendLine("        passHostHeader: true")
            appendLine("        servers:")
            appendLine("          - url: $url")
        }

        serviceFile.writeText(serviceConfig)
    }

    override val reverseProxyRecords: List<ReverseProxyRecord> = emptyList()
    override val webfacingDomains: List<String> = listOf(traefikDomain)
}
