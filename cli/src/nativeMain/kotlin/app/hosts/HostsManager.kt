package app.hosts

import app.SudoManager
import app.storage.storageRoot
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import es.jvbabi.kfile.File
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class HostsManager(
    private val file: File = File("/etc/hosts")
): KoinComponent {
    private val sudoManager by inject<SudoManager>()

    fun getRegisteredHosts(): List<String> {
        val content = file.readText().lines()
        if (!content.any { it == "### WERKBANK HOSTS ###" }) return emptyList()

        return content
            .dropWhile { it != "### WERKBANK HOSTS ###" }
            .drop(1)
            .dropLastWhile { it != "### END WERKBANK HOSTS ###" }
            .dropLast(1)
            .filter { it.isNotBlank() }
            .map { it.substringAfter("127.0.0.1 ") }
    }

    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    fun addHost(host: String) {
        if (host in getRegisteredHosts()) return
        val content = file.readText().lines()
        val newLines = if (!content.any { it == "### WERKBANK HOSTS ###" }) {
            content.toMutableList().apply {
                add("### WERKBANK HOSTS ###")
                add("127.0.0.1 $host")
                add("### END WERKBANK HOSTS ###")
                add("")
            }
        } else {
            content.toMutableList().apply {
                val indexOfEnd = indexOfLast { it == "### END WERKBANK HOSTS ###" }
                add(indexOfEnd, "127.0.0.1 $host")
            }
        }

        val tempFile = File.getTempDirectory().resolve("${Uuid.random()}.hostscopy.txt")
        tempFile.writeText(newLines.joinToString("\n"))

        val hostBackupFile = storageRoot.resolve("host_backups")
            .apply { if (!exists()) mkdir() }
            .resolve("${Clock.System.now().toEpochMilliseconds()}.hosts.backup.txt")
        hostBackupFile.writeText(file.readText())

        if (!sudoManager.canSudo()) {
            println("We need sudo permissions to add the $host to the ${file.absolutePath}")
        }

        val result = Command("sudo")
            .args("cp", tempFile.absolutePath, file.absolutePath)
            .stderr(Stdio.Pipe)
            .spawn()
            .wait()

        if (result != 0) {
            println(buildStyledString { red { +"Failed to write hosts file" } })
            exitProcess(1)
        }
    }
}
