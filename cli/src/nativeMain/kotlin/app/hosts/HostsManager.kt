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

    /**
     * All hosts which resolve to loopback on both IPv4 and IPv6, regardless of whether the entries
     * live inside the werkbank section or somewhere else in the hosts file.
     */
    fun getRegisteredHosts(): List<String> {
        val entries = parseEntries(file.readText().lines())
        return (entries.hostsFor(IPV4_LOOPBACK) intersect entries.hostsFor(IPV6_LOOPBACK)).toList()
    }

    fun addHost(host: String) = addHosts(listOf(host))

    /**
     * Adds every missing loopback entry for [hosts] to the werkbank section. Entries which already
     * exist anywhere in the hosts file are kept as they are, so an IPv6 entry is added later on for
     * hosts which were registered with IPv4 only.
     */
    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    fun addHosts(hosts: List<String>) {
        val content = file.readText().lines()
        val entries = parseEntries(content)
        val existingIpv4 = entries.hostsFor(IPV4_LOOPBACK)
        val existingIpv6 = entries.hostsFor(IPV6_LOOPBACK)

        val missingLines = hosts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .flatMap { host ->
                buildList {
                    if (host.lowercase() !in existingIpv4) add("$IPV4_LOOPBACK $host")
                    if (host.lowercase() !in existingIpv6) add("$IPV6_LOOPBACK $host")
                }
            }

        if (missingLines.isEmpty()) return

        val newLines = insertIntoWerkbankSection(content, missingLines)

        val tempFile = File.getTempDirectory().resolve("${Uuid.random()}.hostscopy.txt")
        tempFile.writeText(newLines.joinToString("\n"))

        val hostBackupFile = storageRoot.resolve("host_backups")
            .apply { if (!exists()) mkdir() }
            .resolve("${Clock.System.now().toEpochMilliseconds()}.hosts.backup.txt")
        hostBackupFile.writeText(file.readText())

        if (!sudoManager.canSudo()) {
            println("We need sudo permissions to add ${hosts.joinToString(", ")} to the ${file.absolutePath}")
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

    private fun insertIntoWerkbankSection(content: List<String>, lines: List<String>): List<String> {
        val startIndex = content.indexOfFirst { it.trim() == SECTION_START }
        val endIndex = content.indexOfLast { it.trim() == SECTION_END }

        if (startIndex != -1 && endIndex > startIndex) {
            return content.toMutableList().apply { addAll(endIndex, lines) }
        }

        return content.toMutableList().apply {
            while (isNotEmpty() && last().isBlank()) removeLast()
            add("")
            add(SECTION_START)
            addAll(lines)
            add(SECTION_END)
            add("")
        }
    }

    private fun parseEntries(content: List<String>): List<HostsEntry> = content.mapNotNull { line ->
        val tokens = line.substringBefore('#').split(' ', '\t').filter { it.isNotBlank() }
        if (tokens.size < 2) return@mapNotNull null
        HostsEntry(ip = tokens.first(), hosts = tokens.drop(1).map { it.lowercase() })
    }

    private fun List<HostsEntry>.hostsFor(ip: String): Set<String> =
        filter { it.ip == ip }.flatMap { it.hosts }.toSet()

    private data class HostsEntry(val ip: String, val hosts: List<String>)

    companion object {
        private const val SECTION_START = "### WERKBANK HOSTS ###"
        private const val SECTION_END = "### END WERKBANK HOSTS ###"
        private const val IPV4_LOOPBACK = "127.0.0.1"
        private const val IPV6_LOOPBACK = "::1"
    }
}
