package commands.certificates

import app.data.Project
import app.dependencies.AppDependency
import app.dependencies.openssl.OpensslHandler
import app.repository.ProjectRepository
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.CHECK_MARK
import util.CROSS_MARK
import util.buildStyledString
import kotlin.system.exitProcess

class CertificatesCommand : SuspendingCliktCommand("certificates"), KoinComponent {
    override val invokeWithoutSubcommand: Boolean = true

    private val opensslHandler by inject<OpensslHandler>()
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))
    private val projectRepository by inject<ProjectRepository>()

    /**
     * One certificate in the tree view. [domains] are the domains the certificate is
     * expected to cover, [errors] is empty for a valid certificate.
     */
    private data class CertificateEntry(
        val name: String,
        val domains: List<String>,
        val errors: List<String>
    )

    private data class Section(
        val title: String,
        val entries: List<CertificateEntry>
    )

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return

        val sections = listOf(
            Section("Root CA", listOf(checkRootCa())),
            Section("Services", dependencies.filter { it.webfacingDomains.isNotEmpty() }.map { checkDependency(it) }),
            Section("Projects", projectRepository.getAllProjects().map { checkProject(it) })
        )

        printTree(sections)

        val errorCount = sections.sumOf { section -> section.entries.sumOf { it.errors.size } }
        if (errorCount > 0) {
            println()
            println(buildStyledString {
                red { +"$CROSS_MARK $errorCount problem${if (errorCount == 1) "" else "s"} found" }
            })
            exitProcess(1)
        }
    }

    private fun checkRootCa(): CertificateEntry {
        val certificateFile = opensslHandler.rootCaFile
        val keyFile = opensslHandler.rootKeyFile
        val errors = mutableListOf<String>()

        errors += checkCertificatePair(
            certificateFile = certificateFile,
            privateKeyFile = keyFile,
            expectedDomains = opensslHandler.rootCaDomains,
            checkSignedByRootCa = false
        )

        if (certificateFile.exists() && !OpensslHandler.isTrusted(certificateFile)) {
            errors += "not trusted by the system, it has to be installed"
        }

        if (!opensslHandler.keyStoreFile.exists()) {
            errors += "keystore is missing: ${opensslHandler.keyStoreFile.absolutePath}"
        }

        return CertificateEntry(
            name = certificateFile.name,
            domains = opensslHandler.rootCaDomains,
            errors = errors
        )
    }

    private fun checkDependency(dependency: AppDependency) = CertificateEntry(
        name = dependency.key,
        domains = dependency.webfacingDomains,
        errors = checkCertificatePair(
            certificateFile = opensslHandler.internalCertificateDirectory.resolve("${dependency.key}.crt"),
            privateKeyFile = opensslHandler.internalCertificateDirectory.resolve("${dependency.key}.key"),
            expectedDomains = dependency.webfacingDomains
        )
    )

    private fun checkProject(project: Project): CertificateEntry {
        val werkbankfile = File(project.path).resolve("Werkbankfile.yaml")
        if (!werkbankfile.exists()) {
            return CertificateEntry(
                name = project.id,
                domains = emptyList(),
                errors = listOf("Werkbankfile.yaml is missing: ${werkbankfile.absolutePath}")
            )
        }

        val domains = project.getCertificateDomains()
        return CertificateEntry(
            name = project.id,
            domains = domains,
            errors = checkCertificatePair(
                certificateFile = project.certificateFile,
                privateKeyFile = project.privateKeyFile,
                expectedDomains = domains
            )
        )
    }

    /**
     * Runs all checks that apply to a certificate and its private key. Checks that depend on
     * an earlier one are skipped instead of reported twice, e.g. domains are only compared
     * once the certificate file is known to exist.
     */
    private fun checkCertificatePair(
        certificateFile: File,
        privateKeyFile: File,
        expectedDomains: List<String>,
        checkSignedByRootCa: Boolean = true
    ): List<String> {
        val errors = mutableListOf<String>()

        if (!certificateFile.exists()) errors += "certificate is missing: ${certificateFile.absolutePath}"
        if (!privateKeyFile.exists()) errors += "private key is missing: ${privateKeyFile.absolutePath}"
        if (errors.isNotEmpty()) return errors

        if (!OpensslHandler.isValidPair(certificateFile, privateKeyFile)) {
            errors += "certificate and private key do not match"
        }

        val actualDomains = OpensslHandler.getDomains(certificateFile)
        val missingDomains = expectedDomains - actualDomains.toSet()
        if (missingDomains.isNotEmpty()) {
            errors += "missing domains: ${missingDomains.joinToString(", ")}"
            errors += "certificate covers: ${actualDomains.ifEmpty { listOf("nothing") }.joinToString(", ")}"
        }

        if (checkSignedByRootCa && !OpensslHandler.isValidChild(opensslHandler.rootCaFile, certificateFile)) {
            errors += "not signed by the current root CA or expired"
        }

        return errors
    }

    private fun printTree(sections: List<Section>) {
        val entries = sections.flatMap { it.entries }
        val nameWidth = entries.maxOfOrNull { it.name.length } ?: 0
        val domainWidth = entries.maxOfOrNull { domainsOf(it).length } ?: 0

        sections.forEach { section ->
            println()
            println(buildStyledString { bold { +section.title } })

            if (section.entries.isEmpty()) {
                println(buildStyledString {
                    gray { +"└─ none" }
                })
                return@forEach
            }

            section.entries.forEachIndexed { index, entry ->
                val isLastEntry = index == section.entries.lastIndex
                println(buildStyledString {
                    gray { +if (isLastEntry) "└─ " else "├─ " }
                    yellow { +entry.name.padEnd(nameWidth) }
                    +"  "
                    gray { +domainsOf(entry).padEnd(domainWidth) }
                    +"  "
                    if (entry.errors.isEmpty()) green { +"$CHECK_MARK valid" }
                    else red { +"$CROSS_MARK invalid" }
                })

                entry.errors.forEachIndexed { errorIndex, error ->
                    val isLastError = errorIndex == entry.errors.lastIndex
                    println(buildStyledString {
                        gray {
                            +if (isLastEntry) "   " else "│  "
                            +if (isLastError) "└─ " else "├─ "
                        }
                        red { +error }
                    })
                }
            }
        }
    }

    private fun domainsOf(entry: CertificateEntry) =
        entry.domains.ifEmpty { listOf("-") }.joinToString(", ")
}
