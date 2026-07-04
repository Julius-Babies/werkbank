package app.werkbank.app.projects.create

import app.werkbank.app.dns.DnsManager
import app.werkbank.app.tools.icon_generator.IconGenerator
import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Project
import app.werkbank.database.Projects
import app.werkbank.database.Service
import app.werkbank.database.ServiceHelper
import app.werkbank.database.Services
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.shared.Werkbankfile
import app.werkbank.shared.setup.SetupResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.koin.ktor.ext.inject

fun Route.createProject() {

    val db by inject<DatabaseManager>()
    val dnsManager by inject<DnsManager>()
    val appConfig by inject<AppConfig>()

    authenticate(AUTH_USER_JWT) {
        post {
            val principal = call.principal<UserPrincipal>()!!
            val werkbankFile = call.receive<Werkbankfile>()
            if (werkbankFile.disallowCloud) return@post
            println(principal.user.username)
            println(werkbankFile)

            val project: Project

            val existingProject = db.query {
                Project
                    .find { (Projects.projectKey eq werkbankFile.project.id) and (Projects.owner eq principal.user.id.value) }
                    .firstOrNull()
            }

            if (existingProject == null) {
                project = db.query {
                    Project.new {
                        this.projectKey = werkbankFile.project.id
                        this.name = werkbankFile.project.name
                        this.owner = principal.user
                        this.icon = ExposedBlob(IconGenerator().generateRandomIcon())
                        this.accessState = Project.AccessState.Disabled
                    }
                }

                val projectDomain = "${werkbankFile.project.id.lowercase()}.${principal.user.username.lowercase()}.${appConfig.domainSuffix}"
                dnsManager.createRecord(projectDomain)

            } else {
                db.query {
                    existingProject.name = werkbankFile.project.name
                }

                project = existingProject
            }

            db.query {
                val servicesToDelete = Service.find { (Services.project eq project.id) and (Services.serviceKey notInList werkbankFile.services.map { it.name }) }
                servicesToDelete.forEach { serviceToDelete ->
                    serviceToDelete.delete()

                    val serviceHelper = ServiceHelper(serviceToDelete)
                    dnsManager.deleteRecord(serviceHelper.getServiceDomain())
                }
            }

            werkbankFile.services.forEach { service ->
                db.query {
                    val existingService = Service.find { (Services.project eq project.id) and (Services.serviceKey eq service.name) }.firstOrNull()
                    if (existingService == null) {
                        val service = Service.new {
                            this.project = project
                            this.serviceKey = service.name
                        }
                        val serviceHelper = ServiceHelper(service)
                        dnsManager.createRecord(serviceHelper.getServiceDomain())
                    }
                }
            }

            call.respond(SetupResponse(
                projectId = project.id.value,
            ))
        }
    }
}
