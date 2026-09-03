package commands.completion.databases

import app.data.extensions.project.postgres18DatabaseNames
import app.repository.ProjectRepository
import commands.completion.CompletionCommand
import org.koin.core.component.inject

/** Postgres 18 databases declared by any project. */
class Postgres18DatabasesCompletionCommand : CompletionCommand("postgres18") {

    private val projectRepository by inject<ProjectRepository>()

    override suspend fun candidates(): Collection<String> = projectRepository.getAllProjects()
        .flatMap { it.postgres18DatabaseNames() }
}
