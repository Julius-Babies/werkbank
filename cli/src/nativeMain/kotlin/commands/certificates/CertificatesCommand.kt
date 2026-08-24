package commands.certificates

import app.storage.cliName
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import util.buildStyledString
import kotlin.system.exitProcess

class CertificatesCommand : SuspendingCliktCommand("certificates") {
    override val invokeWithoutSubcommand: Boolean = true

    private val external by option(
        "--external",
        help = "Also show certificates that were imported from outside, e.g. from the werkbank cloud"
    ).flag()

    private data class Section(val title: String, val entries: List<CertificateEntry>)

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return

        val inspector = CertificateInspector()
        val rootEntry = inspector.inspectRootCa()
        val serviceEntries = inspector.inspectServices()
        val projectEntries = inspector.inspectProjects()
        val externalEntries = if (external) inspector.inspectExternalCertificates() else emptyList()

        printReport(
            buildList {
                add(Section("Root CA", listOf(rootEntry)))
                add(Section("Services", serviceEntries))
                add(Section("Projects", projectEntries))
                if (external) add(Section("External", externalEntries))
            }
        )

        printSummary(
            entries = listOf(rootEntry) + serviceEntries + projectEntries + externalEntries,
            plan = inspector.repairPlan(rootEntry, serviceEntries, projectEntries),
            externalEntries = externalEntries
        )
    }

    private fun printReport(sections: List<Section>) {
        val titleWidth = sections.flatMap { it.entries }.flatMap { it.findings }
            .maxOfOrNull { it.title.length } ?: 0

        sections.forEach { section ->
            println()
            println(buildStyledString { bold { +section.title } })

            if (section.entries.isEmpty()) {
                println(buildStyledString { gray { +"  none" } })
                return@forEach
            }

            // Widths are per section, a single certificate with a long name or many domains
            // must not tear the other sections apart.
            val nameWidth = section.entries.maxOf { it.name.length }
            val domainWidth = section.entries.maxOf { domainsOf(it).length }

            section.entries.forEachIndexed { index, entry ->
                val isLast = index == section.entries.lastIndex
                println(buildStyledString {
                    gray { +if (isLast) "  └─ " else "  ├─ " }
                    yellow { +entry.name.padEnd(nameWidth) }
                    +"  "
                    gray { +domainsOf(entry).padEnd(domainWidth) }
                    +"  "
                    when (entry.status) {
                        CertificateStatus.VALID -> green { +"valid" }
                        CertificateStatus.OUTDATED -> yellow { +"outdated" }
                        CertificateStatus.BROKEN -> red { +"broken" }
                    }
                })

                entry.findings.forEach { finding ->
                    println(buildStyledString {
                        gray { +if (isLast) "     " else "  │  " }
                        +"  "
                        when (finding.status) {
                            CertificateStatus.BROKEN -> red { +finding.title.padEnd(titleWidth) }
                            else -> yellow { +finding.title.padEnd(titleWidth) }
                        }
                        gray { +"  ${finding.detail}" }
                    })
                }
            }
        }
    }

    private fun printSummary(
        entries: List<CertificateEntry>,
        plan: RepairPlan,
        externalEntries: List<CertificateEntry>
    ) {
        val affected = entries.filterNot { it.isValid }

        println()
        if (affected.isEmpty()) {
            println(buildStyledString { green { +"All certificates are valid" } })
            return
        }

        println(buildStyledString {
            val parts = buildList {
                affected.count { it.status == CertificateStatus.BROKEN }.let { if (it > 0) add("$it broken") }
                affected.count { it.status == CertificateStatus.OUTDATED }.let { if (it > 0) add("$it outdated") }
            }
            yellow { +"${parts.joinToString(", ")} of ${entries.size} certificates" }
        })

        if (!plan.isEmpty) {
            println(buildStyledString { gray { +"Repair with" } })
            println(buildStyledString {
                +"  $cliName certificates repair"
                gray { +"   (${plan.commands.joinToString(", ") { "$cliName certificates $it" }})" }
            })
        }

        // Imported certificates cannot be regenerated locally, they have to be fetched again.
        if (externalEntries.any { !it.isValid }) {
            println(buildStyledString { gray { +"Import again with" } })
            println(buildStyledString { +"  $cliName cloud download-certificate" })
        }

        exitProcess(1)
    }

    private fun domainsOf(entry: CertificateEntry) =
        entry.domains.ifEmpty { listOf("-") }.joinToString(", ")

    init {
        subcommands(GenerateCommand(), RepairCommand(), InstallCommand())
    }
}
