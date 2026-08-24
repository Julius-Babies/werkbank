package app.dependencies.openssl

import app.SudoManager
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import es.jvbabi.kfile.File

actual fun installRootCa(rootCaFile: File, sudoManager: SudoManager) {
    TODO("Not yet implemented")
}

actual suspend fun getInstalledRootCAs(sudoManager: SudoManager): List<InstalledRootCa> {
    TODO("Not yet implemented")
}

actual fun uninstallRootCa(fingerprint: String, sudoManager: SudoManager) {
}
actual fun isCertificateTrustedBySystem(certificateFile: File): Boolean {
    val result = Command("openssl")
        .args("verify", certificateFile.absolutePath)
        .stdout(Stdio.Pipe)
        .stderr(Stdio.Pipe)
        .spawn()
        .wait()
    return result == 0
}
