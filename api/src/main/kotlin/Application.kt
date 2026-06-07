package app.werkbank

import app.werkbank.plugins.auth.installAuthentikt
import io.ktor.server.application.Application

fun Application.rootModule() {
    configureKoin()
    configureSerialization()
    installAuthentikt()
    configureRouting()
}
