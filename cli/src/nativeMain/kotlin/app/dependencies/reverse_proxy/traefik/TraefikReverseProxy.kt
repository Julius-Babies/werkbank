package app.dependencies.reverse_proxy.traefik

import app.data.Project
import app.dependencies.AppDependency
import app.dependencies.ReverseProxyRecord
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.dependencies.docker.NetworkConfig
import app.dependencies.openssl.OpensslHandler
import app.dependencies.reverse_proxy.ReverseProxy
import app.dependencies.reverse_proxy.ReverseProxyConfiguration
import app.dependencies.reverse_proxy.ReverseProxyConfigurationResolver
import app.dependencies.reverse_proxy.traefik.config.TraefikHttpConfig
import app.dependencies.reverse_proxy.traefik.config.TraefikTlsConfig
import app.dependencies.reverse_proxy.traefik.config.updateDashboardServiceIfNecessary
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

/**
 * Traefik-backed [ReverseProxy]. All Traefik-specific YAML generation lives in [apply];
 * the routing itself is described by the provider-neutral [ReverseProxyConfiguration]
 * produced by [ReverseProxyConfigurationResolver].
 */
class TraefikReverseProxy : ReverseProxy, KoinComponent {

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
    private val resolver by inject<ReverseProxyConfigurationResolver>()

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

    override suspend fun configure() = apply(resolver.resolve())

    override suspend fun apply(config: ReverseProxyConfiguration) {
        opensslHandler.isOpensslAvailable.await().let { require(it) { "OpenSSL is not available" } }
        generateTraefikConfig()
        writeServiceConfigs(config)
        createDashboardService()
        generateSslConfig()
        config.managedHosts.forEach { hostsManager.addHost(it) }
    }

    override suspend fun provision() {
        if (getContainer().getState() == DockerContainer.State.NotExisting) getContainer().create()
    }

    override suspend fun managedContainers(): List<DockerContainer> = listOf(getContainer())

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
                // domains = [] -> use project base domain
                // domains = null -> don't use any included domains

                httpEntry.domains
                    ?.map {
                        if (it.isBlank()) projectBaseDomain
                        else "${it.lowercase()}.${project.project.id.lowercase()}.werkbank.space"
                    }
                    ?.ifEmpty { listOf(projectBaseDomain) }
                    .orEmpty()
                    .distinct()
            }
        }.distinct()
    }

    override fun isRequiredFor(project: Project): Boolean = true
    override fun isAlwaysRequired(): Boolean = true

    private fun generateTraefikConfig() {
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
            accessLog:
              format: json
        """.trimIndent())
    }

    /**
     * Renders one Traefik dynamic-config file per routing group. Previously generated
     * service files are removed first so stale routes disappear; the dashboard file is
     * kept because it is managed separately.
     */
    private fun writeServiceConfigs(config: ReverseProxyConfiguration) {
        dynamicConfigFolder
            .listFiles()
            .filter { it.name.endsWith(SERVICE_FILE_SUFFIX) && it.name != DASHBOARD_FILE_NAME }
            .forEach { it.delete() }

        config.groups.forEach { group -> writeServiceConfig(group) }
    }

    /**
     * Creates the single SSL file for Traefik, giving it all paths to certificates it can use for services.
     */
    private fun generateSslConfig() {
        val config = TraefikTlsConfig(
            tls = TraefikTlsConfig.Tls(
                certificates = projectRepository.getAllProjects().flatMap { project ->
                    val certificates = mutableListOf(
                        TraefikTlsConfig.Tls.Certificate(
                            certFile = "/projects/${project.id}/certificate.pem",
                            keyFile = "/projects/${project.id}/private.key"
                        )
                    )

                    fun appendFolder(folder: File) {
                        if (!folder.exists()) return
                        val subfolders = folder.listFiles().filter { it.isDirectory }
                        subfolders.forEach { appendFolder(it) }

                        val certFiles =
                            folder.listFiles().filter { !it.isDirectory && it.extension in listOf("pem", "crt") }
                        val keyFiles = folder.listFiles().filter { !it.isDirectory && it.extension == "key" }

                        val pairs = certFiles
                            .associateWith { certFile ->
                                keyFiles.firstOrNull { keyFile ->
                                    OpensslHandler.isValidPair(certFile, keyFile)
                                }
                                    ?: println("Warning: No key file found for certificate ${certFile.absolutePath}").let { null }
                            }
                            .filterValues { it != null }
                            .mapValues { (_, keyFile) -> keyFile!! }

                        pairs.forEach { (certFile, keyFile) ->
                            val certificateFolder = storageRoot.resolve("projects").resolve(project.id)
                            val mountedCertFile = certificateFolder.resolve(certFile.absolutePath.replace("/", "-"))
                            val mountedKeyFile = certificateFolder.resolve(keyFile.absolutePath.replace("/", "-"))
                            certFile.copy(mountedCertFile)
                            keyFile.copy(mountedKeyFile)
                            certificates.add(
                                TraefikTlsConfig.Tls.Certificate(
                                    certFile = "/projects/${project.id}/${mountedCertFile.name}",
                                    keyFile = "/projects/${project.id}/${mountedKeyFile.name}"
                                )
                            )
                        }
                    }

                    project.getConfig()
                        .extraCertificates
                        .map { certRoot -> File(project.path).resolve(certRoot) }
                        .forEach { certRoot ->
                            appendFolder(certRoot)
                        }

                    certificates
                } + dependencies.filter { it.webfacingDomains.isNotEmpty() }.map { dependency ->
                    TraefikTlsConfig.Tls.Certificate(
                        certFile = "/ssl/internal/${dependency.key}.crt",
                        keyFile = "/ssl/internal/${dependency.key}.key"
                    )
                } + opensslHandler.externalCertificateDirectory.listFiles().map { it.nameWithoutExtension }.distinct()
                    .map { domain ->
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

    private fun createDashboardService() {
        hostsManager.addHost(traefikDomain)
        val dashboardConfigFile = dynamicConfigFolder.resolve(DASHBOARD_FILE_NAME)
        updateDashboardServiceIfNecessary(dashboardConfigFile)
    }

    /** Renders a single [group] as a Traefik dynamic-config file. */
    private fun writeServiceConfig(group: ReverseProxyConfiguration.Group) {
        val serviceKey = "${group.name}-service"
        val routers = group.routes.mapIndexed { index, route ->
            "${group.name}-router-$index" to TraefikHttpConfig.Http.Router(
                rule = route.toRule(),
                service = serviceKey,
                priority = route.priority,
            )
        }.toMap()

        val config = TraefikHttpConfig(
            http = TraefikHttpConfig.Http(
                routers = routers,
                services = mapOf(
                    serviceKey to TraefikHttpConfig.Http.Service(
                        loadBalancer = TraefikHttpConfig.Http.Service.LoadBalancer(
                            servers = listOf(TraefikHttpConfig.Http.Service.LoadBalancer.Server(url = group.target.toUrl()))
                        )
                    )
                )
            )
        )
        val yamlContent = Yaml.default.encodeToString(TraefikHttpConfig.serializer(), config)
        val content = group.descriptions.joinToString("\n") { "# $it" }.let {
            if (it.isNotEmpty()) "$it\n$yamlContent" else yamlContent
        }
        dynamicConfigFolder.resolve("${group.name}$SERVICE_FILE_SUFFIX").writeText(content)
    }

    private fun ReverseProxyConfiguration.Target.toUrl(): String = when (this) {
        is ReverseProxyConfiguration.Target.Host -> "http://host.docker.internal:$port"
        is ReverseProxyConfiguration.Target.DockerContainer -> "http://$hostname:$port"
    }

    private fun ReverseProxyConfiguration.Route.toRule(): String {
        val hostRule = "(${hosts.joinToString(" || ") { it.toTraefikRule() }})"
        if (pathPrefixes.isEmpty()) return hostRule
        val pathRule = "(${pathPrefixes.joinToString(" || ") { "PathPrefix(`$it`)" }})"
        return "$hostRule && $pathRule"
    }

    private fun ReverseProxyConfiguration.HostMatch.toTraefikRule(): String = when (this) {
        is ReverseProxyConfiguration.HostMatch.Exact -> "Host(`$domain`)"
        is ReverseProxyConfiguration.HostMatch.SubdomainWildcard ->
            "HostRegexp(`^[^.]+\\.${base.replace(".", "\\.")}$`)"
        is ReverseProxyConfiguration.HostMatch.DeepWildcard ->
            "HostRegexp(`^.+\\.${base.replace(".", "\\.")}$`)"
    }

    override val reverseProxyRecords: List<ReverseProxyRecord> = emptyList()
    override val webfacingDomains: List<String> = listOf(traefikDomain)

    private companion object {
        const val SERVICE_FILE_SUFFIX = ".service.yaml"
        const val DASHBOARD_FILE_NAME = "dashboard.system.service.yaml"
    }
}
