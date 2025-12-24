package commands.poweroff

import app.dependencies.AppDependency
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.buildStyledString

class PoweroffCommand: SuspendingCliktCommand("poweroff"), KoinComponent {
    private val projectRepository by inject<ProjectRepository>()
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))

    override suspend fun run() {
        coroutineScope {
            // Stop all registered dependencies
            dependencies.forEach { dep ->
                launch {
                    println(buildStyledString { blue { +"Stopping dependency '${dep.key}'" } })
                    dep.stop()
                }
            }
            projectRepository.getAllProjects().forEach { project ->
                launch { project.stop() }
            }
        }
    }
}