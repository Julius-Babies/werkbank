package commands.completion

import app.data.extensions.project.getCurrentProjectId
import app.dependencies.AppDependency
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

class CompletionBaseCommand : SuspendingCliktCommand("completion"), KoinComponent {

    private val projectRepository by inject<ProjectRepository>()
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))
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
            "service-key" -> {
                dependencies
                    .filter { it.webfacingDomains.isNotEmpty() }
                    .map { it.key }
                    .sorted()
                    .let { println(it.joinToString(" ")) }
            }
            "project" -> {
                projectRepository.getAllProjects()
                    .map { it.id }
                    .sorted()
                    .let { println(it.joinToString(" ")) }
            }
            "databases" -> {
                val databaseSystem = command[1]
                when (databaseSystem) {
                    "postgres18" -> {
                        val projects = projectRepository.getAllProjects()
                        val desiredDatabases = projects
                            .flatMap { project ->
                                project.getConfig()
                                    .dependencies
                                    ?.postgres
                                    ?.postgres18
                                    ?.databases
                                    .orEmpty().map { dbname ->
                                        project.id + "_" + dbname.substringAfter(project.id + "_")
                                    }
                            }
                            .toSet()
                        println(desiredDatabases.sorted().joinToString(" "))
                    }
                }
            }
            else -> return
        }
    }
}