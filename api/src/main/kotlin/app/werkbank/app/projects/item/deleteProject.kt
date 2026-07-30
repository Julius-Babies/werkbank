package app.werkbank.app.projects.item

import app.werkbank.app.dns.DnsManager
import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Service
import app.werkbank.database.ServiceHelper
import app.werkbank.database.Services
import app.werkbank.plugins.auth.AUTH_USER_JWT
import io.ktor.server.auth.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import org.jetbrains.exposed.v1.core.eq
import org.koin.ktor.ext.inject

fun Route.deleteProject() {

    val db by inject<DatabaseManager>()
    val dnsManager by inject<DnsManager>()
    val appConfig by inject<AppConfig>()

    authenticate(AUTH_USER_JWT) {
        delete {
            val project = call.getProjectWithPrincipalAsOwner() ?: return@delete

            val domains = db.query {
                val projectDomain =
                    "${project.projectKey.lowercase()}.${project.owner.username.lowercase()}.${appConfig.domainSuffix}"
                val serviceDomains = Service
                    .find { Services.project eq project.id }
                    .map { ServiceHelper(it).getServiceDomain() }

                listOf(projectDomain) + serviceDomains
            }

            db.query {
                // Services, password links and tunnel requests are removed via ON DELETE CASCADE
                project.delete()
            }

            domains.forEach { domain ->
                dnsManager.deleteRecord(domain)
            }

            call.respondText("Ok")
        }
    }
}
