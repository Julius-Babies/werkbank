package commands.completion

import app.data.extensions.project.getCurrentProjectId
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class CompletionBaseCommand : SuspendingCliktCommand("completion"), KoinComponent {

    private val projectRepository by inject<ProjectRepository>()
    private val command by argument().multiple()
    override val hiddenFromHelp: Boolean = true

    override suspend fun run() {
        if (command.isEmpty()) return
        val currentProjectId = getCurrentProjectId(true)

        when (command.first()) {
            "service" -> {
                if (currentProjectId == null) return
                projectRepository.getById(currentProjectId)
                    ?.getConfig()
                    ?.services
                    ?.map { it.name }
                    .orEmpty()
                    .sorted()
                    .let { println(it.joinToString(" ")) }
            }
            else -> return
        }
    }
}