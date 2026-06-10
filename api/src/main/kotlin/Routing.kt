package app.werkbank

import app.werkbank.app.login.github.loginWithGitHub
import app.werkbank.app.me.me
import app.werkbank.app.projects.create.createProject
import app.werkbank.app.tunnel.tunnel
import app.werkbank.config.AppConfig
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val appConfig by inject<AppConfig>()
    routing {
        host(appConfig.appDomain) {
            route("/api") {
                route("/login") {
                    route("/github") {
                        loginWithGitHub()
                    }
                }

                route("/tunnel") {
                    tunnel()
                }

                route("/me") {
                    me()
                }

                route("/projects") {
                    createProject()
                }
            }
        }
    }
}