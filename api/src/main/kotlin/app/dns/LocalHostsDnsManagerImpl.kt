package app.werkbank.app.dns

import app.werkbank.app.dns.local.SudoManager
import java.io.File

class LocalHostsDnsManagerImpl(
    private val sudoManager: SudoManager = SudoManager(),
) : DnsManager {

    private companion object {
        private const val BLOCK_START = "### WB APP PUBLIC DOMAINS ###"
        private const val BLOCK_END = "### END WB APP PUBLIC DOMAINS ###"
        private const val HOST_IP = "127.0.0.1"
    }

    override suspend fun createRecord(domain: String): String {
        val hostsFile = File("/etc/hosts")
        val content = if (hostsFile.exists()) hostsFile.readText() else ""

        val entry = "$HOST_IP $domain"

        val newContent = if (content.contains(BLOCK_START) && content.contains(BLOCK_END)) {
            val lines = content.lines()
            val startIdx = lines.indexOfFirst { it.trim() == BLOCK_START }
            val endIdx = lines.indexOfFirst { it.trim() == BLOCK_END }

            if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) {
                appendBlock(content, entry)
            } else {
                val blockLines = lines.subList(startIdx + 1, endIdx)
                if (blockLines.any { it.trim() == entry }) {
                    return domain
                }
                val newLines = lines.toMutableList()
                newLines.add(endIdx, entry)
                newLines.joinToString("\n")
            }
        } else {
            appendBlock(content, entry)
        }

        writeWithSudo(hostsFile.absolutePath, newContent)
        return domain
    }

    override suspend fun deleteRecord(domain: String) {
        val hostsFile = File("/etc/hosts")
        if (!hostsFile.exists()) return

        val content = hostsFile.readText()
        if (!content.contains(BLOCK_START) || !content.contains(BLOCK_END)) return

        val lines = content.lines()
        val startIdx = lines.indexOfFirst { it.trim() == BLOCK_START }
        val endIdx = lines.indexOfFirst { it.trim() == BLOCK_END }
        if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) return

        val blockLines = lines.subList(startIdx + 1, endIdx)
        val entry = "$HOST_IP $domain"
        val lineIdx = blockLines.indexOfFirst { it.trim() == entry }
        if (lineIdx == -1) return

        val newLines = lines.toMutableList()
        newLines.removeAt(startIdx + 1 + lineIdx)
        writeWithSudo(hostsFile.absolutePath, newLines.joinToString("\n"))
    }

    private fun appendBlock(existingContent: String, entry: String): String {
        val suffix = buildString {
            if (existingContent.isNotEmpty() && !existingContent.endsWith("\n")) {
                append('\n')
            }
            appendLine(BLOCK_START)
            appendLine(entry)
            append(BLOCK_END)
            append('\n')
        }
        return existingContent.trimEnd() + "\n" + suffix
    }

    private fun writeWithSudo(path: String, content: String) {
        if (sudoManager.canSudo()) {
            if (!sudoManager.executeWithSudo(listOf("tee", path), stdinInput = content)) {
                throw RuntimeException("Failed to write hosts file using sudo")
            }
        } else {
            val password = sudoManager.promptForPassword()
                ?: throw RuntimeException("Sudo password is required but was not provided")
            if (!sudoManager.executeWithSudo(listOf("tee", path), stdinInput = content, password = password)) {
                throw RuntimeException("Failed to write hosts file with provided sudo password")
            }
        }
    }
}