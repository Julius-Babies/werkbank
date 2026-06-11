package app.werkbank

import app.werkbank.plugins.auth.installAuthentikt
import app.werkbank.plugins.auth.installAuthorization
import app.werkbank.plugins.proxy.SubdomainHandler
import app.werkbank.shared.tunnel.json
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.websocket.*
import java.io.File
import kotlin.time.Duration.Companion.seconds

fun Application.rootModule(
    storageRoot: File
) {
    configureKoin(storageRoot)
//    runBlocking {
//        LocalCertificateManager().requestCertificate(
//            listOf("wbcloud-dev-juliusbabies-midnight.dev.wbspace.app", "*.wbcloud-dev-juliusbabies-midnight.dev.wbspace.app"),
//            targetCertFile = File("/tmp/${Uuid.random()}.crt"),
//            targetKeyFile = File("/tmp/${Uuid.random()}.key")
//        )
//    }
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
    install(CallLogging) {
        format { call ->
            "${call.request.httpMethod.value} ${call.request.host()}${call.request.uri}: ${call.response.status()} in ${call.processingTimeMillis()}ms"
        }
    }
}
