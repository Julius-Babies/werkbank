package app.repository

import app.config.MainConfig
import app.config.WerkbankConfig
import app.data.Project
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
                services = existingProject
                    .services
                    .toMutableList()
                    .let { services ->
                        val providedServiceNames = project.getConfig().services.map { it.name }
                        services.removeAll { it.name !in providedServiceNames }
                        services.forEachIndexed { i, service ->
                            val providedService = project.getConfig().services.first { it.name == service.name }
                            val doesProvidedServiceSupportDocker = providedService.modes.docker != null
                            val doesProvidedServiceSupportLocal = providedService.modes.local != null
                            val currentServiceState = service.serviceState

                            when (currentServiceState) {
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
                        project.getConfig().services.forEach { providedService ->
                            if (providedService.name !in existingServiceNames) {
                                services.add(
                                    WerkbankConfig.Project.Service(
                                        name = providedService.name,
                                        serviceState =
                                            if (providedService.modes.docker != null) WerkbankConfig.Project.Service.ServiceState.Docker
                                            else if (providedService.modes.local != null) WerkbankConfig.Project.Service.ServiceState.Local
                                            else WerkbankConfig.Project.Service.ServiceState.Disabled
                                    )
                                )
                            }
                        }

                        services
                    }
            )
                ?: WerkbankConfig.Project(
                    id = project.id,
                    name = project.name,
                    cloudId = null,
                    path = project.path,
                    submodules = emptyList(),
                    services = project.getConfig().services.map { service ->
                        WerkbankConfig.Project.Service(
                            name = service.name,
                            serviceState =
                                if (service.modes.docker != null) WerkbankConfig.Project.Service.ServiceState.Docker
                                else if (service.modes.local != null) WerkbankConfig.Project.Service.ServiceState.Local
                                else WerkbankConfig.Project.Service.ServiceState.Disabled
                        )
                    }
                )

            return@updateConfig config.copy(
                projects = config.projects.orEmpty().filterNot { it.id == project.id } + newProject
            )
        }

        project.updateHosts()
        project.updateCertificates()
        project.setupProxy()
    }
}