package commands.update

import app.config.MainConfig
import app.werkbank.BuildKonfig
import app.werkbank.shared.cli.update.UpdateResponse
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import es.jvbabi.kfile.File
import http.httpClient
import io.github.z4kn4fein.semver.Version
import io.ktor.client.call.*
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.copyTo
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.FileSizeFormatter
import util.REPLACE_LINE
import util.buildStyledString
import kotlin.time.Duration.Companion.seconds

class UpdateCommand : SuspendingCliktCommand("update"), KoinComponent {

    private val mainConfig by inject<MainConfig>()

    override suspend fun run() {
        val currentVersion = Version.parse(BuildKonfig.version, strict = false)

        val updateState = httpClient()
            .get(URLBuilder("https://${mainConfig.getConfig().werkbankCloudDomain}/api/cli/update/").apply {
                appendPathSegments(currentVersion.preRelease ?: "prod")
                appendPathSegments("check")
                parameters.append("variant", BuildKonfig.variant)
                parameters.append("current_version", currentVersion.toString())
            }.buildString())
            .body<UpdateResponse>()

        when (updateState) {
            UpdateResponse.NoUpdate -> {
                println("\uD83C\uDF89 You are already on the latest version")
                return
            }

            is UpdateResponse.UpdateAvailable -> {
                println(buildStyledString { green { +"There's an update for wb-cli available:" } })
                println()
                println(buildStyledString {
                    +"    "
                    gray { +currentVersion.toString() }
                    +"  →  "
                    bold {
                        aqua {
                            +Version.parse(updateState.version, strict = false).toString()
                        }
                    }
                })
                println()
            }
        }

        val targetFile = File.getTempDirectory().resolve("wbcliupdatefile")
        val downloadResponse = httpClient().prepareGet(updateState.downloadUrl) {
            onDownload { downloadedBytes, ofBytes ->
                val total = ofBytes ?: return@onDownload
                val progress = downloadedBytes.toFloat() / total.toFloat()
                val barWidth = 30
                val filled = (progress * barWidth).toInt()
                print(buildStyledString {
                    +REPLACE_LINE
                    +" "
                    aqua { +"█".repeat(filled) }
                    gray { +"░".repeat(barWidth - filled) }
                    +" ${FileSizeFormatter.format(downloadedBytes)} / ${FileSizeFormatter.format(total)}"
                })
            }
        }.execute()
        if (!downloadResponse.status.isSuccess()) {
            println("Failed to download update file: ${downloadResponse.status}")
            return
        }

        delay(1.seconds)
        downloadResponse.bodyAsChannel().copyTo(targetFile.sink().asByteWriteChannel())
        println(buildStyledString {
            +REPLACE_LINE
            green { +"Download complete" }
        })

        targetFile.setExecutable(true)
        targetFile.copy(File.getCurrentExecutableFile())
        println(buildStyledString {
            green { +"Done! " }
            +"Werkbank CLI has been updated to version ${updateState.version}"
        })
    }
}