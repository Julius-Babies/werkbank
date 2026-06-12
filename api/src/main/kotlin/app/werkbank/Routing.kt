package app.werkbank

import app.werkbank.app.login.callback
import app.werkbank.app.login.github.loginWithGitHub
import app.werkbank.app.login.login
import app.werkbank.app.login.logout
import app.werkbank.app.me.me
import app.werkbank.app.projects.create.createProject
import app.werkbank.app.tunnel.tunnel
import app.werkbank.app.webapp.socket.webappSocket
import app.werkbank.config.AppConfig
import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val appConfig by inject<AppConfig>()
    routing {
        host(Regex("^(([a-zA-Z0-9]|-)+\\.)?${appConfig.appDomain.replace(".", "\\.")}$")) {
            route("/api") {
                route("/login") {
                    login()

                    route("/logout") {
                        logout()
                    }

                    route("/github") {
                        loginWithGitHub()
                    }

                    route("/callback") {
                        callback()
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

                route("/webapp") {
                    route("/ws") {
                        webappSocket()
                    }
                }
            }
        }
    }
}