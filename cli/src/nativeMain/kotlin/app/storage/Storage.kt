package app.storage

import es.jvbabi.kfile.File
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
val isDevMode by lazy {
    File.getWorkingDirectory().resolve("devmode").exists() || getenv("DEV")?.toKString() == "true"
}

val storageRoot by lazy {
    if (isDevMode) File.getUserHomeDirectory().resolve(".werkbankdev").apply { if (!exists()) mkdir() }
    else File.getUserHomeDirectory().resolve(".werkbank").apply { if (!exists()) mkdir() }
}