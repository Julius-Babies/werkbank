package commands.completion

import app.repository.ProjectRepository
import org.koin.core.component.inject

/** Ids of all projects known to werkbank. */
class ProjectCompletionCommand : CompletionCommand("project") {

    private val projectRepository by inject<ProjectRepository>()

    override suspend fun candidates(): Collection<String> = projectRepository.getAllProjects()
        .map { it.id }
}
