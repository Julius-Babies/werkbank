package commands.cloud

import app.config.MainConfig
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import commands.cloud.download_certificate.DownloadCertificateCommand
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class CloudCommand : SuspendingCliktCommand("cloud"), KoinComponent {

    private val mainConfig by inject<MainConfig>()
    override val invokeWithoutSubcommand: Boolean = true

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return

        println("Using ${mainConfig.getConfig().werkbankCloudDomain}")

        val auth = mainConfig.getConfig().auth
        if (auth == null) {
            println("Not logged in, use wb login")
        } else {
            println("Hello, ${auth.username}")
        }
    }

    init {
        this.subcommands(
            DownloadCertificateCommand(),
        )
    }
}