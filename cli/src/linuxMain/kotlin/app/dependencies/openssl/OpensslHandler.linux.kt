package app.dependencies.openssl

import app.SudoManager
import es.jvbabi.kfile.File

actual fun installRootCa(rootCaFile: File, sudoManager: SudoManager) {
    TODO("Not yet implemented")
}

actual suspend fun getInstalledRootCAs(sudoManager: SudoManager): List<InstalledRootCa> {
    TODO("Not yet implemented")
}

actual fun uninstallRootCa(fingerprint: String, sudoManager: SudoManager) {
}