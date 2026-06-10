package app.werkbank

import app.werkbank.plugins.auth.installAuthentikt
import app.werkbank.plugins.auth.installAuthorization
import app.werkbank.plugins.subdomain.SubdomainHandler
import app.werkbank.shared.tunnel.json
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds

fun Application.rootModule() {
    configureKoin()
    configureSerialization()
    installAuthentikt()
    installAuthorization()
    install(SubdomainHandler)
    install(WebSockets) {
        maxFrameSize = Long.MAX_VALUE
        pingPeriod = 15.seconds
        contentConverter = KotlinxWebsocketSerializationConverter(json)
    }
    configureRouting()
    install(CallLogging)
}
