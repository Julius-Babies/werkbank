package commands.update

import app.config.MainConfig
import app.werkbank.BuildKonfig
import app.werkbank.shared.cli.update.UpdateResponse
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import http.httpClient
import io.github.z4kn4fein.semver.Version
import io.ktor.client.call.*
import io.ktor.client.request.*
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
            UpdateResponse.NoUpdate -> println("You are already on the latest version")
            is UpdateResponse.UpdateAvailable -> println("Update available: ${updateState.version}")
        }
    }
}