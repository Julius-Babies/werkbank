package commands.service.state

import app.config.WerkbankConfig
import app.data.extensions.project.getCurrentProjectId
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.parameters.arguments.argument
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class StateCommand(
    serviceCallback: () -> String
) : SuspendingCliktCommand("state"), KoinComponent {

    private val projectRepository by inject<ProjectRepository>()
    private val serviceName by lazy { serviceCallback() }

    val state by argument(
        name = "state",
        help = "The service state",
        completionCandidates = CompletionCandidates.Fixed("off", "local", "docker")
    )

    override suspend fun run() {
        val projectId = getCurrentProjectId() ?: return
        val project = projectRepository.getById(projectId)!!
        println(serviceName)
        val service = project.getConfig().services.first { it.name == serviceName }
        when (state) {
            "off" -> {
                project.setServiceStateTo(service.name, WerkbankConfig.Project.Service.ServiceState.Disabled)
            }
            "local" -> {
                project.setServiceStateTo(service.name, WerkbankConfig.Project.Service.ServiceState.Local)
            }
            "docker" -> {
                project.setServiceStateTo(service.name, WerkbankConfig.Project.Service.ServiceState.Docker)
            }
        }
    }
}