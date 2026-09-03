package commands.completion

import app.data.extensions.project.getCurrentProjectId
import app.repository.ProjectRepository
import org.koin.core.component.inject

/** Services of the project in the current working directory. */
class ServiceCompletionCommand : CompletionCommand("service") {

    private val projectRepository by inject<ProjectRepository>()

    override suspend fun candidates(): Collection<String> {
        val projectId = getCurrentProjectId(inAutocompleteContext = true) ?: return emptyList()
        return projectRepository.getById(projectId)
            ?.getConfig()
            ?.services
            ?.map { it.name }
            .orEmpty()
    }
}
