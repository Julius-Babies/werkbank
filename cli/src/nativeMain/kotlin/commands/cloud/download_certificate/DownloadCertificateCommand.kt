package commands.cloud.download_certificate

import app.config.MainConfig
import app.dependencies.openssl.OpensslHandler
import app.werkbank.shared.download_certificate.DownloadResponse
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import http.httpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.io.encoding.Base64
import kotlin.system.exitProcess

class DownloadCertificateCommand: SuspendingCliktCommand("download-certificate"), KoinComponent {

    private val mainConfig by inject<MainConfig>()
    private val openSslHandler by inject<OpensslHandler>()

    override suspend fun run() {
        val auth = mainConfig.getConfig().auth
        if (auth == null) {
            println("Not logged in, use wb login")
            exitProcess(1)
        }

        val response = httpClient().get("https://${mainConfig.getConfig().werkbankCloudDomain}/api/cli/download-certificates") {
            bearerAuth(auth.bearer)
        }
        if (!response.status.isSuccess()) {
            println("Failed to download certificate: ${response.bodyAsText()}")
            exitProcess(1)
        }

        val body = response.body<DownloadResponse>()
        when (body) {
            is DownloadResponse.NotFound -> {
                println("No certificates in your account")
                return
            }
            else -> {}
        }
        body as DownloadResponse.Success
        openSslHandler.externalCertificateDirectory.resolve("${mainConfig.getConfig().werkbankCloudDomain}.${auth.username}.crt").writeBytes(Base64.decode(body.certificate))
        openSslHandler.externalCertificateDirectory.resolve("${mainConfig.getConfig().werkbankCloudDomain}.${auth.username}.key").writeBytes(Base64.decode(body.privateKey))
        println("Successfully imported user-certificate.")
    }
}