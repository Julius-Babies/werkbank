package app.werkbank.data.repository

import app.werkbank.config.AppConfig
import io.github.z4kn4fein.semver.Version
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

class CliBinaryRepositoryImpl: CliBinaryRepository, KoinComponent {
    private val appConfig by inject<AppConfig>()

    private val binaryStorage = File(appConfig.cli.storageDir).also { it.mkdirs() }

    override suspend fun setCurrentVersion(version: Version, binaryFiles: List<File>) {
        val channel = binaryStorage.resolve(version.preRelease ?: "production").apply { mkdirs() }
        val currentVersionFile = channel.resolve("current-version.txt")
        currentVersionFile.writeText(version.toString())
        binaryFiles.forEach { it.copyTo(channel.resolve(it.name), overwrite = true) }
    }

    override suspend fun getCurrentVersion(channel: String): Version? {
        val channel = binaryStorage.resolve(channel).apply { mkdirs() }
        val currentVersionFile = channel.resolve("current-version.txt")
        return currentVersionFile.takeIf { it.exists() }?.readText()?.let { Version.parse(it) }
    }

    override suspend fun getCliBinary(variant: String, channel: String): File? {
        val channel = binaryStorage.resolve(channel).apply { mkdirs() }
        return channel.resolve("cli-$variant").takeIf { it.exists() }
    }
}