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

    fun importProject(project: Project) {
        mainConfig.updateConfig { config ->
            val existingProject = config.projects.orEmpty().firstOrNull { it.id == project.id }
            val newProject = existingProject?.copy(
                path = project.path,
                name = project.name
            )
                ?: WerkbankConfig.Project(
                    id = project.id,
                    name = project.name,
                    path = project.path,
                    submodules = emptyList()
                )

            return@updateConfig config.copy(
                projects = config.projects.orEmpty().filterNot { it.id == project.id } + newProject
            )
        }
    }
}