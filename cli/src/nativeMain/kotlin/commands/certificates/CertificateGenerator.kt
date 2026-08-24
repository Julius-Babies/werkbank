package commands.certificates

import app.data.Project
import app.dependencies.AppDependency
import app.dependencies.openssl.OpensslHandler
import app.repository.ProjectRepository
import app.storage.cliName
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.buildStyledString
import kotlin.system.exitProcess
import kotlin.test.assertTrue

/**
 * Shared implementation behind `certificates generate`. Regenerating the root CA invalidates
 * every certificate signed by it, so [generate] always covers services and projects as well
 * once [root] is set.
 */
internal class CertificateGenerator : KoinComponent {
    private val opensslHandler by inject<OpensslHandler>()
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))
    private val projectRepository by inject<ProjectRepository>()

    val allServices get() = dependencies.filter { it.webfacingDomains.isNotEmpty() }
    val allProjects get() = projectRepository.getAllProjects()

    /** Resolves the keys of `--service`, exits with a hint when one of them is unknown. */
    fun servicesByKeys(keys: List<String>): List<AppDependency> = keys.map { key ->
        allServices.firstOrNull { it.key == key } ?: unknown("service", key, allServices.map { it.key })
    }

    /** Resolves the ids of `--project`, exits with a hint when one of them is unknown. */
    fun projectsByIds(ids: List<String>): List<Project> = ids.map { id ->
        allProjects.firstOrNull { it.id == id } ?: unknown("project", id, allProjects.map { it.id })
    }

    private fun unknown(type: String, value: String, known: List<String>): Nothing {
        println(buildStyledString { red { +"Unknown $type: $value" } })
        println(buildStyledString { gray { +"Known ${type}s: ${known.sorted().joinToString(", ")}" } })
        exitProcess(1)
    }

    suspend fun generate(
        root: Boolean,
        services: List<AppDependency>,
        projects: List<Project>
    ) {
        assertTrue(opensslHandler.isOpensslAvailable.await())

        var servicesToGenerate = services
        var projectsToGenerate = projects

        if (root) {
            opensslHandler.createRootCa()
            // Everything signed by the old root CA is worthless now.
            servicesToGenerate = allServices
            projectsToGenerate = allProjects
        }

        if (servicesToGenerate.isNotEmpty()) {
            println(buildStyledString { bold { +"Services" } })
            servicesToGenerate.forEach { service ->
                println(buildStyledString {
                    +"  "
                    yellow { +service.key }
                    gray { +"  ${service.webfacingDomains.joinToString(", ")}" }
                })
                opensslHandler.createInternalCertificates(listOf(service), force = true)
            }
            println()
        }

        if (projectsToGenerate.isNotEmpty()) {
            println(buildStyledString { bold { +"Projects" } })
            projectsToGenerate.forEach { project ->
                println(buildStyledString {
                    +"  "
                    yellow { +project.id }
                    gray { +"  ${project.getCertificateDomains().joinToString(", ")}" }
                })
                project.updateCertificates()
            }
            println()
        }

        println(buildStyledString { green { +"Certificates generated" } })
        println(buildStyledString { gray { +"Run '$cliName certificates' to verify, '$cliName up' to apply them." } })
    }
}
