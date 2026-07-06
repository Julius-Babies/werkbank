package commands.update

import app.config.MainConfig
import app.werkbank.BuildKonfig
import app.werkbank.shared.cli.update.UpdateResponse
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import es.jvbabi.kfile.File
import http.httpClient
import io.github.z4kn4fein.semver.Version
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.copyTo
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class UpdateCommand: SuspendingCliktCommand("update"), KoinComponent {

    private val mainConfig by inject<MainConfig>()

    override suspend fun run() {
        val currentVersion = Version.parse(BuildKonfig.version, strict = false)
        val client = httpClient()

        val updateState = httpClient()
            .get("https://${mainConfig.getConfig().werkbankCloudDomain}/api/cli/update/${currentVersion.preRelease ?: "prod"}/check?variant=${BuildKonfig.variant}&current_version=$currentVersion")
            .body<UpdateResponse>()

        when (updateState) {
            UpdateResponse.NoUpdate -> {
                println("You are already on the latest version")
                return
            }
            is UpdateResponse.UpdateAvailable -> println("Update available: ${updateState.version} at ${updateState.downloadUrl}")
        }

        val targetFile = File.getTempDirectory().resolve("wbcliupdatefile")
        val downloadResponse = httpClient().get(updateState.downloadUrl)
        if (!downloadResponse.status.isSuccess()) {
            println("Failed to download update file: ${downloadResponse.status}")
            return
        }

        println("Downloading update file...")
        downloadResponse.bodyAsChannel().copyTo(targetFile.sink().asByteWriteChannel())

        println("Update downloaded")
        println("Installing update...")
        targetFile.setExecutable(true)
        targetFile.copy(File.getCurrentExecutableFile())
        println("Update installed")
    }
}