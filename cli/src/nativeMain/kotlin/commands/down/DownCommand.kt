package commands.down

import app.data.extensions.project.getCurrentProjectId
import app.dependencies.DependencyOrchestrator
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DownCommand : SuspendingCliktCommand("down"), KoinComponent {

    private val projectRepository by inject<ProjectRepository>()
    private val orchestrator by inject<DependencyOrchestrator>()

    override suspend fun run() {
        val projectId = getCurrentProjectId() ?: return
        val project = projectRepository.getById(projectId)!!
        orchestrator.down(project)
    }
}
