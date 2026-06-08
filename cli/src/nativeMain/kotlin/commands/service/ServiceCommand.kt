package commands.service

import app.data.extensions.project.getCurrentProjectId
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import commands.service.state.StateCommand
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.system.exitProcess

class ServiceCommand : SuspendingCliktCommand("service"), KoinComponent {
    private val projectRepository by inject<ProjectRepository>()

    val service by argument(
        name = "service",
        help = "The service name",
        completionCandidates = CompletionCandidates.Custom.fromStdout("wb completion service")
    )

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) {
            val projectId = getCurrentProjectId() ?: return
            val project = projectRepository.getById(projectId)!!
            if (project.getConfig().services.none { it.name == service }) {
                println(buildStyledString {
                    red { +"Service $service not found in project ${project.name}" }
                })
                exitProcess(1)
            }
        }
    }

    init {
        this.subcommands(
            StateCommand(
                serviceCallback = { service }
            )
        )
    }
}