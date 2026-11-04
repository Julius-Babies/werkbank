package app.repository

import app.config.MainConfig
import app.config.WerkbankConfig
import app.data.Project
import app.data.defaultServiceState
import app.storage.cliName
import app.storage.storageRoot
import app.werkbank.shared.Werkbankfile
import es.jvbabi.kfile.File
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import platform.posix.fputs
import platform.posix.stderr
import util.buildStyledString

class ProjectRepository : KoinComponent {
    private val mainConfig by inject<MainConfig>()

    /**
     * Returns all registered projects. Projects whose Werkbankfile no longer exists (because the
     * project got moved or deleted) are removed from the config on the fly.
     */
    fun getAllProjects(): List<Project> {
        val (existingProjects, missingProjects) = mainConfig.getConfig().projects.orEmpty()
            .partition { File(it.path).resolve(WERKBANKFILE_NAME).exists() }
        if (missingProjects.isNotEmpty()) removeProjects(missingProjects)

        return existingProjects.map { project ->
            Project(
                id = project.id,
                name = project.name,
                path = project.path
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun removeProjects(projects: List<WerkbankConfig.Project>) {
        val ids = projects.map { it.id }.toSet()
        mainConfig.updateConfig { config ->
            config.copy(projects = config.projects.orEmpty().filterNot { it.id in ids })
        }

        projects.forEach { project ->
            val storage = storageRoot.resolve("projects").resolve(project.id)
            if (storage.exists()) storage.delete(recursive = true)

            // stderr, so that shell completions are not polluted
            val message = buildStyledString {
                yellow { +"Removed project ${project.name} (${project.id}): no Werkbankfile found at ${project.path}. Run \"$cliName setup\" in its new location if it was moved." }
            }
            fputs(message + "\n", stderr)
        }
    }

    fun getById(id: String): Project? {
        return getAllProjects().firstOrNull { it.id == id }
    }

    suspend fun importProject(project: Project) {
        mainConfig.updateConfig { config ->
            val existingProject = config.projects.orEmpty().firstOrNull { it.id == project.id }
            val newProject = existingProject?.copy(
                path = project.path,
                name = project.name,
                services = mergeServices(existingProject.services, project.getConfig().services)
            )
                ?: WerkbankConfig.Project(
                    id = project.id,
                    name = project.name,
                    cloudId = null,
                    path = project.path,
                    submodules = emptyList(),
                    services = mergeServices(emptyList(), project.getConfig().services)
                )

            return@updateConfig config.copy(
                projects = config.projects.orEmpty().filterNot { it.id == project.id } + newProject
            )
        }

        project.updateHosts()
        project.updateCertificates()
        project.setupProxy()
    }

    /**
     * Keeps the states of services that still exist (downgrading them if their mode vanished),
     * drops services that are gone and adds new ones with their default state.
     */
    private fun mergeServices(
        existingServices: List<WerkbankConfig.Project.Service>,
        providedServices: List<Werkbankfile.Service>,
    ): List<WerkbankConfig.Project.Service> {
        val providedServiceNames = providedServices.map { it.name }
        val services = existingServices.filter { it.name in providedServiceNames }.toMutableList()

        services.forEachIndexed { i, service ->
            val providedService = providedServices.first { it.name == service.name }
            val doesProvidedServiceSupportDocker = providedService.modes.docker != null
            val doesProvidedServiceSupportLocal = providedService.modes.local != null

            when (service.serviceState) {
                WerkbankConfig.Project.Service.ServiceState.Docker -> {
                    if (!doesProvidedServiceSupportDocker) {
                        if (doesProvidedServiceSupportLocal) services[i] = service.copy(serviceState = WerkbankConfig.Project.Service.ServiceState.Local)
                        else services[i] = service.copy(serviceState = WerkbankConfig.Project.Service.ServiceState.Disabled)
                    }
                }
                WerkbankConfig.Project.Service.ServiceState.Local -> {
                    if (!doesProvidedServiceSupportLocal) services[i] = service.copy(serviceState = WerkbankConfig.Project.Service.ServiceState.Disabled)
                }
                else -> Unit
            }
        }

        val existingServiceNames = services.map { it.name }.toSet()
        providedServices
            .filterNot { it.name in existingServiceNames }
            .forEach { providedService ->
                services.add(
                    WerkbankConfig.Project.Service(
                        name = providedService.name,
                        serviceState = providedService.defaultServiceState()
                    )
                )
            }

        return services
    }

    companion object {
        const val WERKBANKFILE_NAME = "Werkbankfile.yaml"
    }
}
