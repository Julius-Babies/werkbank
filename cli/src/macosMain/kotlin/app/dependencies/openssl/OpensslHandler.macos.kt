package app.dependencies.openssl

import app.SudoManager
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import es.jvbabi.kfile.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import util.buildStyledString
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

actual fun installRootCa(rootCaFile: File, sudoManager: SudoManager) {
    if (!sudoManager.canSudo()) println("We need sudo permissions to install the root CA.")
    val result = Command("sudo")
        .args("security", "add-trusted-cert", "-d", "-r", "trustRoot", "-k", "/Library/Keychains/System.keychain", rootCaFile.absolutePath)
        .stderr(Stdio.Pipe)
        .spawn()
        .wait()
    if (result != 0) println(buildStyledString { red { +"Failed to install root CA." } })
}

@OptIn(ExperimentalAtomicApi::class)
actual suspend fun getInstalledRootCAs(sudoManager: SudoManager): List<InstalledRootCa> {
    val securityResult = Command("sudo")
        .args("security", "find-certificate", "-a", "-Z", "/Library/Keychains/System.keychain")
        .stderr(Stdio.Inherit)
        .stdout(Stdio.Pipe)
        .spawn()

    val awkResult = Command("awk")
        .args("/SHA-1/{fp=$3} /\"labl\"/{gsub(/.*<blob>=/, \"\", $0); print fp, $0}")
        .stderr(Stdio.Inherit)
        .stdout(Stdio.Pipe)
        .stdin(Stdio.Pipe)
        .spawn()

    val securityStdout = securityResult.bufferedStdout()!!
    val awkStdin = awkResult.bufferedStdin()!!
    val awkStdout = awkResult.bufferedStdout()!!

    val result = mutableListOf<InstalledRootCa>()
    val stdinClosed = AtomicBoolean(false)

    coroutineScope {
        val writer = launch(Dispatchers.IO) {
            try {
                securityStdout.lines().forEach { line ->
                    awkStdin.writeLine(line)
                }
            } finally {
                if (stdinClosed.compareAndSet(false, true)) {
                    awkStdin.close()
                }
            }
        }

        val reader = launch(Dispatchers.IO) {
            awkStdout.lines().forEach { line ->
                // ex: CC05A28AC27DCB57E18EB6A2E05C2A6B77C32C04 "Werkbank Root CA"
                val fingerprint = line.substringBefore(" ")
                val name = line.substringAfter(" ")
                    .drop(1) // Remove leading "
                    .dropLast(1) // Remove trailing "
                result += InstalledRootCa(fingerprint, name)
            }
        }

        writer.join()
        reader.join()
    }

    securityResult.wait()
    return result
}

actual fun uninstallRootCa(fingerprint: String, sudoManager: SudoManager) {
    if (!sudoManager.canSudo()) println("We need sudo permissions to uninstall the root CA.")
    val result = Command("sudo")
        .args("security", "delete-certificate", "-Z", fingerprint, "/Library/Keychains/System.keychain")
        .stderr(Stdio.Pipe)
        .spawn()
        .wait()
    if (result != 0) println(buildStyledString { red { +"Failed to uninstall root CA." } })
}