package app.werkbank

import app.queue.certificate.CertificateQueue
import app.werkbank.plugins.auth.installAuthentikt
import app.werkbank.plugins.auth.installAuthorization
import app.werkbank.plugins.proxy.SubdomainHandler
import app.werkbank.shared.tunnel.json
import app.werkbank.util.launchConnectionJob
import io.ktor.serialization.kotlinx.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.websocket.*
import org.koin.ktor.ext.inject
import plugins.configureOpenTelemetry
import java.io.File
import kotlin.time.Duration.Companion.seconds

fun Application.rootModule(
    storageRoot: File
) {
    configureKoin(storageRoot)
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
    configureOpenTelemetry()
    install(CallLogging) {
        format { call ->
            "${call.request.httpMethod.value} ${call.request.host()}${call.request.uri}: ${call.response.status()} in ${call.processingTimeMillis()}ms"
        }
    }

    val certificateQueue by inject<CertificateQueue>()
    // Must not run as a bare `launch` on the Application scope: an uncaught exception would cancel the
    // application, after which the Koin plugin closes the root scope and every request fails with
    // ClosedScopeException. launchConnectionJob contains and logs failures instead. See [launchConnectionJob].
    launchConnectionJob(this, "certificate-queue") { certificateQueue.start() }
}
