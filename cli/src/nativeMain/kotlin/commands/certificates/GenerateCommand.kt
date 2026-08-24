package commands.certificates

import app.storage.cliName
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import util.buildStyledString
import kotlin.system.exitProcess

class GenerateCommand : SuspendingCliktCommand("generate") {
    override val invokeWithoutSubcommand: Boolean = true

    private val root by option("--root", help = "Regenerate the root CA. Implies --services and --projects").flag()
    private val services by option("--services", help = "Regenerate the certificates of all werkbank services").flag()
    private val projects by option("--projects", help = "Regenerate the certificates of all projects").flag()

    private val service by option(
        "--service",
        help = "Regenerate the certificates of the given services (comma separated)",
        metavar = "KEY,KEY",
        completionCandidates = CompletionCandidates.Custom.fromStdout("$cliName completion service-key")
    ).split(",")

    private val project by option(
        "--project",
        help = "Regenerate the certificates of the given projects (comma separated)",
        metavar = "ID,ID",
        completionCandidates = CompletionCandidates.Custom.fromStdout("$cliName completion project")
    ).split(",")

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return

        val generator = CertificateGenerator()
        val selectedServices =
            if (services) generator.allServices else generator.servicesByKeys(service.orEmpty())
        val selectedProjects =
            if (projects) generator.allProjects else generator.projectsByIds(project.orEmpty())

        if (!root && selectedServices.isEmpty() && selectedProjects.isEmpty()) {
            println(buildStyledString { red { +"Nothing selected" } })
            println(buildStyledString {
                gray { +"Use '$cliName certificates generate all' or one of --root, --services, --projects, --service, --project" }
            })
            exitProcess(1)
        }

        generator.generate(root = root, services = selectedServices, projects = selectedProjects)
    }

    init {
        subcommands(GenerateAllCommand())
    }
}

class GenerateAllCommand : SuspendingCliktCommand("all") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Regenerates the root CA and every certificate signed by it"

    override suspend fun run() {
        CertificateGenerator().generate(root = true, services = emptyList(), projects = emptyList())
    }
}
