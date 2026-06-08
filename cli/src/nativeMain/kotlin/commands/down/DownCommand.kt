package commands.down

import app.data.extensions.project.getCurrentProjectId
import app.data.extensions.project.hasRunningContainers
import app.data.extensions.project.usesPostgres18
import app.data.extensions.project.usesTraefik
import app.dependencies.postgres.Postgres18
import app.dependencies.reverse_proxy.TraefikManager
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class DownCommand : SuspendingCliktCommand("down"), KoinComponent {

    private val projectRepository by inject<ProjectRepository>()
    private val postgres18 by inject<Postgres18>()
    private val traefikManager by inject<TraefikManager>()

    override suspend fun run() {
        val projectId = getCurrentProjectId() ?: return
        val project = projectRepository.getById(projectId)!!
        val otherProjects = projectRepository.getAllProjects().filter { it.id != projectId }
        coroutineScope {
            launch {
                println(buildStyledString { cyan { +"Stopping project ${project.name}..." } })
                project.stop()
            }
            launch {
                if (project.usesPostgres18()) {
                    if (otherProjects.all { !it.usesPostgres18() || !it.hasRunningContainers() }) {
                        println(buildStyledString { cyan { +"Stopping Postgres 18..." } })
                        postgres18.container.stop()
                    }
                }
            }
            launch {
                if (project.usesTraefik()) {
                    if (otherProjects.all { !it.usesTraefik() || !it.hasRunningContainers() }) {
                        println(buildStyledString { cyan { +"Stopping Traefik..." } })
                        traefikManager.getContainer().stop()
                    }
                }
            }
        }
    }
}