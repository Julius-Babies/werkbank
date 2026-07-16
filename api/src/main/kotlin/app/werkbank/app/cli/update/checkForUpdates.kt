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
        if (!hasDownloadFile || latestVersion == null || !isNewer(latestVersion, currentVersion)) {
            call.respond<UpdateResponse>(UpdateResponse.NoUpdate)
            return@get
        }

        call.respond<UpdateResponse>(UpdateResponse.UpdateAvailable(
            version = latestVersion.toString(),
            downloadUrl = "https://${appConfig.appDomain}/api/cli/update/$channel/download/$variant"
        ))
    }
}

/**
 * Determines whether [latest] should be offered as an update over [current].
 *
 * SemVer 2.0.0 (and therefore the semver library) ignores build metadata when
 * comparing versions. Our versioning scheme encodes the incrementing build
 * number in exactly that metadata (e.g. `0.0.1-alpha+7`), so a plain `>` would
 * treat `+5` and `+7` as equal. When core version and prerelease match, fall
 * back to a numeric comparison of the build metadata.
 */
private fun isNewer(latest: Version, current: Version): Boolean {
    val cmp = latest.compareTo(current)
    if (cmp != 0) return cmp > 0

    val latestBuild = latest.buildMetadata?.toLongOrNull() ?: 0L
    val currentBuild = current.buildMetadata?.toLongOrNull() ?: 0L
    return latestBuild > currentBuild
}