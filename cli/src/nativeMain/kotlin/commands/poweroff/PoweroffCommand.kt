package commands.poweroff

import app.dependencies.postgres.Postgres18
import app.dependencies.reverse_proxy.TraefikManager
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PoweroffCommand: SuspendingCliktCommand("poweroff"), KoinComponent {
    private val traefikManager by inject<TraefikManager>()
    private val postgres18 by inject<Postgres18>()
    private val projectRepository by inject<ProjectRepository>()

    override suspend fun run() {
        coroutineScope {
            launch { traefikManager.container.stop() }
            launch { postgres18.container.stop() }
            projectRepository.getAllProjects().forEach { project ->
                launch { project.stop() }
            }
        }
    }
}