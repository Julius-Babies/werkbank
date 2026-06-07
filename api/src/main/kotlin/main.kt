package app.werkbank

import io.ktor.server.engine.*
import io.ktor.server.application.*
import io.ktor.server.netty.Netty
import io.ktor.server.routing.Routing

fun main() {
    embeddedServer(
        factory = Netty,
        port = 8080,
        host = "0.0.0.0",
        module = Application::rootModule
    ).start(wait = true)
}
