package app.werkbank

import app.werkbank.app.cli.download_certificate.downloadCertificates
import app.werkbank.app.cli.ImportCliBinaries
import app.werkbank.app.cli.update.checkForUpdates
import app.werkbank.app.cli.update.downloadCli
import app.werkbank.app.login.callback
import app.werkbank.app.login.github.loginWithGitHub
import app.werkbank.app.login.login
import app.werkbank.app.login.logout
import app.werkbank.app.me.me
import app.werkbank.app.projects.create.createProject
import app.werkbank.app.projects.item.projectRoutes
import app.werkbank.app.proxy.auth.password.proxyPassword
import app.werkbank.app.proxy.auth.proxyAuthLanding
import app.werkbank.app.proxy.auth.proxyAuthResult
import app.werkbank.app.tunnel.tunnel
import app.werkbank.app.webapp.projects.item.access.getState
import app.werkbank.app.webapp.projects.item.access.passwords.getPasswordOptions
import app.werkbank.app.webapp.projects.webappProjects
import app.werkbank.app.webapp.requests.getRequests
import app.werkbank.app.webapp.requests.item.downloadTunnelRequestBody
import app.werkbank.app.webapp.requests.item.getRequest
import app.werkbank.app.webapp.settings.access_keys.createAccessKey
import app.werkbank.app.webapp.settings.access_keys.getAccessKeys
import app.werkbank.app.webapp.settings.access_keys.deleteAccessKey
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

                route("/proxy") {
                    route("/auth") {
                        route("/landing") {
                            proxyAuthLanding()
                        }

                        route("/result") {
                            proxyAuthResult()
                        }

                        route("/password") {
                            proxyPassword()
                        }
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

                    route("/{projectId}") {
                        projectRoutes()
                    }
                }

                route("/webapp") {
                    route("/projects") {
                        webappProjects()

                        route("/{projectId}") {
                            route("/access") {
                                route("/passwords") {
                                    route("/options") {
                                        getPasswordOptions()
                                    }
                                }

                                getState()
                            }
                        }
                    }

                    route("/settings") {
                        route("/access-keys") {
                            route("/{accessKeyId}") {
                                deleteAccessKey()
                            }

                            createAccessKey()
                            getAccessKeys()
                        }
                    }

                    route("/ws") {
                        webappSocket()
                    }

                    route("/requests") {
                        route("/{requestId}") {
                            route("/download-body") {
                                downloadTunnelRequestBody()
                            }

                            getRequest()
                        }

                        getRequests()
                    }
                }

                route("/cli") {
                    route("/download-certificates") {
                        downloadCertificates()
                    }

                    route("/update") {
                        route("/{channel}") {
                            route("/check") {
                                checkForUpdates()
                            }

                            route("/download") {
                                route("/{variant}") {
                                    downloadCli()
                                }
                            }
                        }
                    }
                }

                route("/webhook") {
                    route("/github") {
                        route("/cli") {
                            route("/release") {
                                val importCliBinaries by inject<ImportCliBinaries>()
                                importCliBinaries.installIn(this)
                            }
                        }
                    }
                }
            }
        }
    }
}