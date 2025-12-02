package commands.up

import app.dependencies.postgres.Postgres18
import app.dependencies.reverse_proxy.TraefikManager
import app.repository.ProjectRepository
import com.charleskorn.kaml.Yaml
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import commands.setup.Werkbankfile
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class UpCommand: SuspendingCliktCommand("up"), KoinComponent {
    private val traefikManager by inject<TraefikManager>()
    private val postgres18 by inject<Postgres18>()

    private val projectRepository by inject<ProjectRepository>()

    val startInfrastructure by option("--start-infrastructure", help = "Starts the infrastructure")
        .flag()

    override suspend fun run() {
        var currentDirectory = File.getWorkingDirectory()
        var werkbankfile = currentDirectory.resolve("Werkbankfile.yaml")

        while (currentDirectory.parent != null && !werkbankfile.exists()) {
            currentDirectory = currentDirectory.parent!!
            werkbankfile = currentDirectory.resolve("Werkbankfile.yaml")
        }

        if (startInfrastructure) {
            traefikManager.initialize()
            traefikManager.getContainer().start()

            postgres18.initialize(true)
            postgres18.container.start()
        }

        if (!werkbankfile.exists()) {
            if (!startInfrastructure) println(buildStyledString { red { +"No Werkbankfile found in current directory or any parent directory" } })
            return
        }

        val werkbankFileContent = werkbankfile.readText()
        val werkbankFile = Yaml.default.decodeFromString(Werkbankfile.serializer(), werkbankFileContent)
        val projectId = werkbankFile.project.id
        val project = projectRepository.getAllProjects().firstOrNull { it.id == projectId } ?: error("Project with id $projectId not found")
        if (project.getConfig().services.isNotEmpty()) {
            traefikManager.initialize()
            traefikManager.getContainer().start()
        }

        if (project.getConfig().dependencies?.postgres?.postgres18 != null) {
            postgres18.initialize(true)
            postgres18.container.start()
        }

        project.start()
    }
}