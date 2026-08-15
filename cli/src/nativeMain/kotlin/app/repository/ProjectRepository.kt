package app.repository

import app.config.MainConfig
import app.config.WerkbankConfig
import app.data.Project
import app.data.defaultServiceState
import app.werkbank.shared.Werkbankfile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ProjectRepository : KoinComponent {
    private val mainConfig by inject<MainConfig>()

    fun getAllProjects(): List<Project> {
        val config = mainConfig.getConfig()
        return config.projects.orEmpty().map { project ->
            Project(
                id = project.id,
                name = project.name,
                path = project.path
            )
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
}