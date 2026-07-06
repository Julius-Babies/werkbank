package app.werkbank.app.cli.update

import app.werkbank.config.AppConfig
import app.werkbank.data.repository.CliBinaryRepository
import app.werkbank.shared.cli.update.UpdateResponse
import io.github.z4kn4fein.semver.Version
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

fun Route.checkForUpdates() {
    val cliBinaryRepository by inject<CliBinaryRepository>()
    val appConfig by inject<AppConfig>()

    get {
        val channel = call.parameters["channel"] ?: "production"
        val currentVersion = run {
            val raw = call.parameters["current_version"] ?: return@run Version.parse("0.0.0", strict = false)
            Version.parse(raw, strict = false)
        }
        val variant = call.parameters["variant"]!!

        val latestVersion = cliBinaryRepository.getCurrentVersion(channel)
        val hasDownloadFile = cliBinaryRepository.getCliBinary(variant, channel) != null
        if (!hasDownloadFile || latestVersion == null || currentVersion >= latestVersion) {
            call.respond<UpdateResponse>(UpdateResponse.NoUpdate)
            return@get
        }

        call.respond<UpdateResponse>(UpdateResponse.UpdateAvailable(
            version = latestVersion.toString(),
            downloadUrl = "https://${appConfig.appDomain}/api/cli/update/$channel/download/$variant"
        ))
    }
}