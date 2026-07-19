package app.dependencies.reverse_proxy

import app.config.MainConfig
import app.config.WerkbankConfig
import app.dependencies.AppDependency
import app.dependencies.ReverseProxyRecord
import app.dependencies.reverse_proxy.ReverseProxyConfiguration.Group
import app.dependencies.reverse_proxy.ReverseProxyConfiguration.HostMatch
import app.dependencies.reverse_proxy.ReverseProxyConfiguration.Route
import app.dependencies.reverse_proxy.ReverseProxyConfiguration.Target
import app.repository.ProjectRepository
import app.storage.isDevMode
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.buildStyledString

/**
 * Builds the provider-neutral [ReverseProxyConfiguration] from the current project
 * and dependency state. The result is consumed by any [ReverseProxy] implementation,
 * so no Traefik (or other provider) specifics may leak in here.
 */
class ReverseProxyConfigurationResolver : KoinComponent {
    private val projectRepository by inject<ProjectRepository>()
    private val mainConfig by inject<MainConfig>()
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))

    fun resolve(): ReverseProxyConfiguration {
        val internalRecords = dependencies.flatMap { dependency ->
            dependency.reverseProxyRecords.map { record -> dependency.key to record }
        }
        val groups = resolveProjectGroups() + internalRecords.map { (key, record) -> internalGroup(key, record) }
        val managedHosts = internalRecords.map { (_, record) -> record.domain }.distinct()
        return ReverseProxyConfiguration(groups = groups, managedHosts = managedHosts)
    }

    /** One group per exposed project service, mirroring the project's `http` entries. */
    private fun resolveProjectGroups(): List<Group> {
        val projects = projectRepository.getAllProjects().associate { it.getWerkbankConfig() to it.getConfig() }
        return projects.flatMap { (state, project) ->
            val projectBaseDomains = listOfNotNull(
                "${project.project.id.lowercase()}.werkbank.space",
                project.project.externalDomain,
            )

            project.http.groupBy { it.targetService }.mapNotNull { (targetServiceName, httpEntries) ->
                val targetService = project.services.firstOrNull { it.name == targetServiceName }
                if (targetService == null) {
                    println(buildStyledString { red { +"HTTP entry references unknown service '$targetServiceName' in project '${project.project.name}'" } })
                    return@mapNotNull null
                }
                val serviceState = state.services.firstOrNull { it.name == targetServiceName }?.serviceState
                if (serviceState == null || serviceState == WerkbankConfig.Project.Service.ServiceState.Disabled) return@mapNotNull null

                val serviceName = project.project.id.lowercase() + "-" + targetServiceName.lowercase()

                val target = when (serviceState) {
                    WerkbankConfig.Project.Service.ServiceState.Docker -> {
                        val dockerMode = targetService.modes.docker ?: error("Service $targetServiceName has no docker mode")
                        val containerName = "werkbank${if (isDevMode) "-dev" else ""}-${project.project.id.lowercase()}-${dockerMode.container}"
                        Target.DockerContainer(hostname = containerName, port = dockerMode.port)
                    }
                    WerkbankConfig.Project.Service.ServiceState.Local -> {
                        val localMode = targetService.modes.local ?: error("Service $targetServiceName has no local mode")
                        Target.Host(port = localMode.port)
                    }
                    WerkbankConfig.Project.Service.ServiceState.Disabled -> return@mapNotNull null
                }

                val descriptions = mutableListOf<String>()
                val routes = httpEntries.map { httpEntry ->
                    val werkbankDomains = httpEntry.domains
                        ?.flatMap {
                            if (it.isBlank()) projectBaseDomains
                            else projectBaseDomains.map { baseDomain -> "${it.lowercase()}.${project.project.id.lowercase()}.$baseDomain" }
                        }
                        ?.ifEmpty { projectBaseDomains }
                        .orEmpty()
                        .distinct()
                    val externalDomains = httpEntry.externalDomains
                        .filterNot { it.isBlank() }
                        .map { it.toHostMatch() }
                    val cloudDomains = mainConfig.getConfig().auth?.username?.let { username ->
                        if (project.disallowCloud) emptyList()
                        else listOf(HostMatch.Exact("${targetServiceName.lowercase()}-${project.project.id.lowercase()}.${username.lowercase()}.localwb.space"))
                    } ?: emptyList()
                    httpEntry.description?.let { descriptions.add(it) }

                    Route(
                        hosts = werkbankDomains.map { HostMatch.Exact(it) } + externalDomains + cloudDomains,
                        pathPrefixes = httpEntry.pathPrefixes.ifEmpty { listOf("/") },
                        priority = httpEntry.priority,
                    )
                }

                Group(name = serviceName, target = target, routes = routes, descriptions = descriptions)
            }
        }
    }

    /** One group per internal dependency endpoint (e.g. the Keycloak or Jaeger UI). */
    private fun internalGroup(dependencyKey: String, record: ReverseProxyRecord): Group = Group(
        name = "${dependencyKey}-${record.domain.replace(".", "-")}",
        target = Target.DockerContainer(hostname = record.containerName, port = record.port),
        routes = listOf(Route(hosts = listOf(HostMatch.Exact(record.domain)))),
    )

    private fun String.toHostMatch(): HostMatch = when {
        startsWith("**.") -> HostMatch.DeepWildcard(removePrefix("**."))
        startsWith("*.") -> HostMatch.SubdomainWildcard(removePrefix("*."))
        else -> HostMatch.Exact(this)
    }
}
