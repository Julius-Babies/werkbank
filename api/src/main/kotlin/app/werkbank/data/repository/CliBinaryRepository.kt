package app.werkbank.data.repository

import io.github.z4kn4fein.semver.Version
import java.io.File

interface CliBinaryRepository {
    suspend fun setCurrentVersion(
        version: Version,
        binaryFiles: List<File>,
    )

    suspend fun getCurrentVersion(channel: String): Version?

    suspend fun getCliBinary(variant: String, channel: String): File?
}