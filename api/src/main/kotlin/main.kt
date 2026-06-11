package app.werkbank

import app.certificates.LocalCertificateManager
import app.certificates.ServerKeyManager
import app.werkbank.config.AppConfig
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.path
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.netty.handler.ssl.OptionalSslHandler
import io.netty.handler.ssl.SslContextBuilder
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject
import rootModule
import util.StubSpan
import java.io.File
import kotlin.uuid.Uuid

fun main(args: Array<String>) {
    runBlocking {
        AppCommand().main(args)
    }
}

class AppCommand : SuspendingCliktCommand("server") {
    val storageDirectory by option("--storage-directory", help = "Path to store data in")
        .path(mustExist = true, mustBeWritable = true, mustBeReadable = true)
        .default(File("./data").toPath())

    val bindHost by option("--bind-host", help = "Host to bind to")
        .optionalValue("")

    val port by option("--port", help = "Port to bind to")
        .default("8080")
        .validate { require(it.toIntOrNull() != null) { "Port must be a number" } }

    val withLocalMainCertificate by option("--with-local-main-certificate", help = "Use local main certificate").flag(default = false)

    override suspend fun run() {

        val sslContext = SslContextBuilder
            .forServer(ServerKeyManager())
            .build()

        embeddedServer(
            factory = Netty,
            configure = {
                connector {
                    host = this@AppCommand.bindHost ?: "0.0.0.0"
                    port = this@AppCommand.port.toInt()
                }
                channelPipelineConfig = {
                    addFirst(OptionalSslHandler(sslContext))
                }
            },
            module = {
                rootModule(storageDirectory.toFile())

                if (withLocalMainCertificate) {
                    val appConfig by inject<AppConfig>()
                    val localCertificateManager by inject<LocalCertificateManager>()
                    localCertificateManager.requestCertificate(
                        span = StubSpan,
                        listOf(appConfig.appDomain, "*." + appConfig.appDomain),
                        targetCertFile = File("/tmp/${Uuid.random()}.crt"),
                        targetKeyFile = File("/tmp/${Uuid.random()}.key")
                    )
                }
            }
        ).start(wait = true)
    }
}