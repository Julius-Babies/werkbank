package app.werkbank

import app.werkbank.plugins.auth.installAuthentikt
import app.werkbank.plugins.auth.installAuthorization
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging

fun Application.rootModule() {
    configureKoin()
    configureSerialization()
    installAuthentikt()
    installAuthorization()
    configureRouting()
    install(CallLogging)
}
