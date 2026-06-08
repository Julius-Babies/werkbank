package commands.service.state

import app.config.WerkbankConfig
import app.data.extensions.project.getCurrentProjectId
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.parameters.arguments.argument
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.RIGHT_ARROW
import util.buildStyledString
import kotlin.system.exitProcess

class StateCommand(
    serviceCallback: () -> String
) : SuspendingCliktCommand("state"), KoinComponent {

    private val projectRepository by inject<ProjectRepository>()
    private val serviceName by lazy { serviceCallback() }

    val targetStateString by argument(
        name = "state",
        help = "The service state",
        completionCandidates = CompletionCandidates.Fixed("off", "local", "docker")
    )

    override suspend fun run() {
        val targetState = when (targetStateString) {
            "off" -> WerkbankConfig.Project.Service.ServiceState.Disabled
            "local" -> WerkbankConfig.Project.Service.ServiceState.Local
            "docker" -> WerkbankConfig.Project.Service.ServiceState.Docker
            else -> {
                println(buildStyledString {
                    red { +"The state must be one of: off, local, docker - got $targetStateString" }
                })
                exitProcess(1)
            }
        }

        val projectId = getCurrentProjectId() ?: return
        val project = projectRepository.getById(projectId)!!
        val service = project.getConfig().services.first { it.name == serviceName }
        val currentState = project.getWerkbankConfig().services.first { it.name == serviceName }.serviceState
        if (currentState == targetState) {
            if (targetState != WerkbankConfig.Project.Service.ServiceState.Docker) {
                println(buildStyledString {
                    yellow { +"Service $serviceName is already in state $targetStateString" }
                })
                exitProcess(0)
            }
            println("Restarting Docker container for service $serviceName")
        } else {
            println(buildStyledString {
                cyan { +currentState.getString().padEnd(8, ' ') }
                gray { +RIGHT_ARROW }
                green { +targetState.getString().padStart(8, ' ') }
            })
        }
        when (targetStateString) {
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

private fun WerkbankConfig.Project.Service.ServiceState.getString() = when (this) {
    WerkbankConfig.Project.Service.ServiceState.Disabled -> "Off"
    WerkbankConfig.Project.Service.ServiceState.Local -> "Local"
    WerkbankConfig.Project.Service.ServiceState.Docker -> "Docker"
}