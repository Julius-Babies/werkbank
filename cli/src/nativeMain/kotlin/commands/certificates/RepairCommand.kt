package commands.certificates

import app.dependencies.openssl.OpensslHandler
import app.storage.cliName
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class RepairCommand : SuspendingCliktCommand("repair"), KoinComponent {
    private val opensslHandler by inject<OpensslHandler>()

    override fun help(context: Context) =
        "Regenerates every certificate that 'certificates' reports as outdated or broken"

    override suspend fun run() {
        val inspector = CertificateInspector()
        val plan = inspector.repairPlan(
            rootEntry = inspector.inspectRootCa(),
            serviceEntries = inspector.inspectServices(),
            projectEntries = inspector.inspectProjects()
        )

        if (plan.isEmpty) {
            println(buildStyledString { green { +"All certificates are valid, nothing to repair" } })
            return
        }

        plan.commands.forEach { command ->
            println(buildStyledString { gray { +"$cliName certificates $command" } })
        }
        println()

        if (plan.installRoot) opensslHandler.installRootCaInSystem()

        if (plan.regenerateRoot || plan.serviceKeys.isNotEmpty() || plan.projectIds.isNotEmpty()) {
            val generator = CertificateGenerator()
            generator.generate(
                root = plan.regenerateRoot,
                services = generator.servicesByKeys(plan.serviceKeys),
                projects = generator.projectsByIds(plan.projectIds)
            )
        }
    }
}
