package app.werkbank

import io.ktor.server.application.Application

fun Application.rootModule() {
    configurePostgres()
    configureExposed()
    configureKoin()
    configureSerialization()
    configureRouting()
}
