package app.werkbank

import app.werkbank.app.login.github.loginWithGitHub
import app.werkbank.app.me.me
import app.werkbank.app.projects.create.createProject
import app.werkbank.config.AppConfig
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val appConfig by inject<AppConfig>()
    routing {
        host(appConfig.domainSuffix) {
            route("/api") {
                route("/login") {
                    route("/github") {
                        loginWithGitHub()
                    }
                }

                route("/me") {
                    me()
                }

                route("/projects") {
                    createProject()
                }
            }
        }

        host(Regex(".+\\.${appConfig.domainSuffix.replace(".", "\\.")}")) {
            get("*") {
                call.respondText("Hey")
            }
        }
    }
}