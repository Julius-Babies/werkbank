package commands.setup

import app.config.MainConfig
import app.data.Project
import app.dependencies.AppDependency
import app.dependencies.keycloak.Keycloak
import app.repository.ProjectRepository
import app.werkbank.shared.Werkbankfile
import com.charleskorn.kaml.Yaml
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import es.jvbabi.kfile.File
import http.httpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.buildStyledString

class SetupCommand : SuspendingCliktCommand("setup"), KoinComponent {

    private val projectRepository by inject<ProjectRepository>()
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))
    private val mainConfig by inject<MainConfig>()

    override val invokeWithoutSubcommand: Boolean = true
    override suspend fun run() {
        val currentDirectory = File.getWorkingDirectory()
        val werkbankfile = currentDirectory.resolve("Werkbankfile.yaml")

        if (!werkbankfile.exists()) {
            println(buildStyledString { red { +"No Werkbankfile found in current directory" } })
            return
        }

        val werkbankFileContent = werkbankfile.readText()
        val werkbankFile = Yaml.default.decodeFromString(Werkbankfile.serializer(), werkbankFileContent)

        val projects = projectRepository.getAllProjects()
        projects.firstOrNull { it.id == werkbankFile.project.id }?.let { existingProject ->
            if (existingProject.path != currentDirectory.absolutePath) {
                println(buildStyledString { yellow { +"Project with id ${werkbankFile.project.id} already exists in another directory" } })
                println("By continuing, the path of the existing project will be overwritten. This can be useful if the project got moved.")
                println(buildStyledString {
                    +"Continue? "
                    gray { +"(y/n)" }
                })

                val response = readln()
                if (response.lowercase() != "y") return
            }
            println(buildStyledString { gray { +"Project with id ${werkbankFile.project.id} already exists, updating config" } })
        }

        projectRepository.importProject(Project(
            id = werkbankFile.project.id,
            name = werkbankFile.project.name,
            path = currentDirectory.absolutePath
        ))

        println(buildStyledString { green { +"Project ${werkbankFile.project.name} successfully imported" } })

        if (werkbankFile.dependencies?.keycloak == true) {
            val keycloak = dependencies.filterIsInstance<Keycloak>().firstOrNull()
                ?: error("Keycloak dependency not found")
            keycloak.initialize()
            keycloak.start()
            keycloak.ensureRealm(
                projectId = werkbankFile.project.id,
                projectName = werkbankFile.project.name
            )
        }

        if (mainConfig.getConfig().auth != null && !werkbankFile.disallowCloud) {
            println(buildStyledString { gray { +"Uploading Werkbankfile" } })
            val client = httpClient()
            val response = client.post("https://werkbank.werkbank.space/api/projects") {
                contentType(ContentType.Application.Json)
                setBody(werkbankFile)
                bearerAuth(mainConfig.getConfig().auth!!.bearer)
            }
            println(response.bodyAsText())
        }
    }
}
