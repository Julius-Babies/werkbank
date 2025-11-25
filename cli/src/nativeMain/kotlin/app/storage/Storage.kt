package app.storage

import es.jvbabi.kfile.File

val isDevMode by lazy {
    File.getWorkingDirectory().resolve("devmode").exists()
}

val storageRoot by lazy {
    if (isDevMode) File.getWorkingDirectory().resolve("_data").apply { if (!exists()) mkdir() }
    else File.getUserHomeDirectory().resolve(".werkbank").apply { if (!exists()) mkdir() }
}