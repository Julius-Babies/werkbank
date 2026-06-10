package app.dependencies.reverse_proxy

import app.config.MainConfig
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
    private val mainConfig by inject<MainConfig>()

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
                Container.VolumeBind.Host(opensslHandler.externalCertificateDirectory.absolutePath, readOnly = true) to "/ssl/external",
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
            project.http.flatMap { httpEntry ->
                httpEntry.domains
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
                } + opensslHandler.externalCertificateDirectory.listFiles().map { it.nameWithoutExtension }.distinct().map { domain ->
                    TraefikTlsConfig.Tls.Certificate(
                        certFile = "/ssl/external/${domain}.crt",
                        keyFile = "/ssl/external/${domain}.key"
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
            val projectBaseDomains = listOfNotNull("${project.project.id.lowercase()}.werkbank.space", project.project.externalDomain)

            project.http.groupBy { it.targetService }.forEach { (targetServiceName, httpEntries) ->
                val targetService = project.services.firstOrNull { it.name == targetServiceName }
                if (targetService == null) {
                    println(buildStyledString { red { +"HTTP entry references unknown service '$targetServiceName' in project '${project.project.name}'" } })
                    return@forEach
                }
                val serviceState = state.services.firstOrNull { it.name == targetServiceName }?.serviceState
                if (serviceState == null || serviceState == WerkbankConfig.Project.Service.ServiceState.Disabled) return@forEach

                val serviceName = project.project.id.lowercase() + "-" + targetServiceName.lowercase()

                val url = when (serviceState) {
                    WerkbankConfig.Project.Service.ServiceState.Docker -> {
                        val dockerMode = targetService.modes.docker ?: error("Service $targetServiceName has no docker mode")
                        val container = dockerMode.container
                        val port = dockerMode.port
                        val containerName = "werkbank${if (isDevMode) "-dev" else ""}-${project.project.id.lowercase()}-${container}"
                        "http://${containerName}:$port"
                    }
                    WerkbankConfig.Project.Service.ServiceState.Local -> {
                        val localMode = targetService.modes.local ?: error("Service $targetServiceName has no local mode")
                        "http://host.docker.internal:${localMode.port}"
                    }
                }

                val routers = mutableMapOf<String, TraefikHttpConfig.Http.Router>()
                val descriptions = mutableListOf<String>()

                httpEntries.forEachIndexed { index, httpEntry ->
                    val werkbankDomains = httpEntry.domains
                        .flatMap {
                            if (it.isBlank()) projectBaseDomains
                            else projectBaseDomains.map { baseDomain -> "${it.lowercase()}.${project.project.id.lowercase()}.$baseDomain" }
                        }
                        .ifEmpty { projectBaseDomains }
                        .distinct()
                    val externalDomains = httpEntry.externalDomains
                        .filterNot { it.isBlank() }
                        .map { it.toTraefikHostRule() }
                    val werkbankCloudDomain = mainConfig.getConfig().auth?.username?.let { username ->
                        if (project.disallowCloud) emptyList()
                        else listOf("${targetServiceName.lowercase()}-${project.project.id.lowercase()}.${username.lowercase()}.localwb.space".toTraefikHostRule())
                    } ?: emptyList()
                    val allDomains = werkbankDomains.map { "Host(`$it`)" } + externalDomains + werkbankCloudDomain
                    val pathPrefixes = httpEntry.pathPrefixes.ifEmpty { listOf("/") }
                    val rule = "(${allDomains.joinToString(" || ")}) && (${pathPrefixes.joinToString(" || ") { "PathPrefix(`$it`)" }})"

                    routers["${serviceName}-router-$index"] = TraefikHttpConfig.Http.Router(
                        rule = rule,
                        service = "${serviceName}-service",
                        priority = httpEntry.priority,
                    )
                    httpEntry.description?.let { descriptions.add(it) }
                }

                writeServiceConfig(
                    serviceFile = dynamicConfigFolder.resolve("${serviceName}.user.service.yaml"),
                    serviceName = serviceName,
                    routers = routers,
                    url = url,
                    descriptions = descriptions,
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
        val rule = "Host(`${record.domain}`)"
        val routers = mapOf(
            "${serviceName}-router" to TraefikHttpConfig.Http.Router(
                rule = rule,
                service = "${serviceName}-service",
            )
        )

        writeServiceConfig(
            serviceFile = serviceFile,
            serviceName = serviceName,
            routers = routers,
            url = "http://${record.containerName}:${record.port}",
        )
    }

    private fun writeServiceConfig(
        serviceFile: File,
        serviceName: String,
        routers: Map<String, TraefikHttpConfig.Http.Router>,
        url: String,
        descriptions: List<String> = emptyList(),
    ) {
        val config = TraefikHttpConfig(
            http = TraefikHttpConfig.Http(
                routers = routers,
                services = mapOf(
                    "${serviceName}-service" to TraefikHttpConfig.Http.Service(
                        loadBalancer = TraefikHttpConfig.Http.Service.LoadBalancer(
                            servers = listOf(TraefikHttpConfig.Http.Service.LoadBalancer.Server(url = url))
                        )
                    )
                )
            )
        )
        val yamlContent = Yaml.default.encodeToString(TraefikHttpConfig.serializer(), config)
        val content = descriptions.joinToString("\n") { "# $it" }.let {
            if (it.isNotEmpty()) "$it\n$yamlContent" else yamlContent
        }
        serviceFile.writeText(content)
    }

    private fun String.toTraefikHostRule(): String = when {
        startsWith("**.") -> {
            val base = removePrefix("**.").replace(".", "\\.")
            "HostRegexp(`.+\\." + base + "`)"
        }
        startsWith("*.") -> {
            val base = removePrefix("*.").replace(".", "\\.")
            "HostRegexp(`[^.]+\\." + base + "`)"
        }
        else -> "Host(`$this`)"
    }


    override val reverseProxyRecords: List<ReverseProxyRecord> = emptyList()
    override val webfacingDomains: List<String> = listOf(traefikDomain)
}
