package app.dependencies.openssl

import app.SudoManager
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import es.jvbabi.kfile.File
import util.buildStyledString

actual fun installRootCa(rootCaFile: File, sudoManager: SudoManager) {
    if (!sudoManager.canSudo()) println("We need sudo permissions to install the root CA.")
    val result = Command("sudo")
        .args("security", "add-trusted-cert", "-d", "-r", "trustRoot", "-k", "/Library/Keychains/System.keychain", rootCaFile.absolutePath)
        .stderr(Stdio.Pipe)
        .spawn()
        .wait()
    if (result != 0) println(buildStyledString { red { +"Failed to install root CA." } })
}