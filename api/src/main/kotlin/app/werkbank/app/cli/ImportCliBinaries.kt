package app.werkbank.app.cli

import app.werkbank.config.AppConfig
import app.werkbank.data.repository.CliBinaryRepository
import app.werkbank.plugins.auth.AUTH_GITHUB_ACTIONS_WERKBANK_REPOSITORY
import app.werkbank.util.forEachAsync
import app.werkbank.util.mapAsync
import io.github.z4kn4fein.semver.Version
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.util.cio.use
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream


class ImportCliBinaries: KoinComponent {
    private val appConfig by inject<AppConfig>()
    private val cliBinaryRepository by inject<CliBinaryRepository>()

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
            })
        }


        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(appConfig.github.apiToken, null)
                }
            }
        }
    }

    suspend fun import(runId: String) {
        val workflowArtifacts = httpClient.get("https://api.github.com/repos/Julius-Babies/werkbank/actions/runs/$runId/artifacts")
        val artifacts = workflowArtifacts.body<WorkflowArtifacts>().artifacts

        val cliMetadataDownloadUrl = artifacts.first { it.name == "cli-metadata" }.archiveDownloadUrl
        val cliMetadataZipFile = File(appConfig.storage.temporaryDir, "cli-metadata.json.zip")
        download(cliMetadataDownloadUrl, cliMetadataZipFile)
        unzip(cliMetadataZipFile)

        val cliMetadataFile = File(appConfig.storage.temporaryDir, "cli-metadata.json")

        val cliMetadata = getCliMetadata(cliMetadataFile)
        val latestVersion = Version.parse(cliMetadata.version, strict = false)

        val cliBinaries = coroutineScope {
            artifacts
                .filter { it.name.startsWith("cli-") }
                .filterNot { it.name == "cli-metadata" }
                .mapAsync { artifact ->
                    downloadCliBinary(artifact.name, artifact.archiveDownloadUrl)
                }
        }

        println(latestVersion)
        println(cliBinaries)
        cliBinaryRepository.setCurrentVersion(
            version = latestVersion,
            binaryFiles = cliBinaries,
        )
    }

    private suspend fun downloadCliBinary(variant: String, downloadUrl: String): File {
        val zipFile = File(appConfig.storage.temporaryDir, "$variant.zip")
        download(downloadUrl, zipFile)
        unzip(zipFile)
        val cliFile = File(appConfig.storage.temporaryDir, variant)
        cliFile.setExecutable(true)
        return cliFile
    }

    private suspend fun download(url: String, to: File) {
        val response = httpClient.get(url)
        require(response.status.isSuccess()) { "Failed to download $url: ${response.status}" }
        response.bodyAsChannel().toInputStream().use { inputStream ->
            to.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    private suspend fun unzip(file: File) {
        withContext(Dispatchers.IO) {
            ZipInputStream(FileInputStream(file)).use { zip ->
                var zipEntry = zip.getNextEntry()
                val buffer = ByteArray(1024)
                while (zipEntry != null) {
                    val newFile = File(file.parentFile.absolutePath + "/" + zipEntry)
                    if (zipEntry.isDirectory) {
                        if (!newFile.isDirectory() && !newFile.mkdirs()) {
                            throw IOException("Failed to create directory $newFile")
                        }
                    } else {
                        val parent = newFile.getParentFile()
                        if (!parent.isDirectory() && !parent.mkdirs()) {
                            throw IOException("Failed to create directory $parent")
                        }

                        val fos = FileOutputStream(newFile)
                        var len: Int
                        while ((zip.read(buffer).also { len = it }) > 0) {
                            fos.write(buffer, 0, len)
                        }
                        fos.close()
                    }
                    zipEntry = zip.getNextEntry()
                }
            }
        }
    }

    private fun getCliMetadata(cliMetadata: File): CliMetadata {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = false
            allowTrailingComma = true
        }
        return json.decodeFromString(cliMetadata.readText())
    }

    fun installIn(route: Route) {
        with(route) {
            authenticate(AUTH_GITHUB_ACTIONS_WERKBANK_REPOSITORY, optional = false) {
                post {
                    val runId = call.parameters["run_id"] ?: return@post call.respondText("Missing run_id parameter", status = HttpStatusCode.BadRequest)
                    import(runId)
                    call.respondText("OK")
                }
            }
        }
    }
}

@Serializable
private data class WorkflowArtifacts(
    @SerialName("artifacts") val artifacts: List<Artifact>
) {
    @Serializable
    data class Artifact(
        @SerialName("name") val name: String,
        @SerialName("archive_download_url") val archiveDownloadUrl: String,
    )
}

@Serializable
private data class CliMetadata(
    @SerialName("version") val version: String,
)