package app.process

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix._SC_CLK_TCK
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.sysconf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `/proc/<pid>/cmdline` separates the arguments with NUL bytes. */
private const val NUL = '\u0000'

/** Everything comes out of `/proc`, so no process has to be spawned to inspect another one. */
actual fun processInfo(pid: Int): ProcessInfo? {
    // cmdline holds the arguments separated by NUL bytes; comm is the truncated executable name and
    // the only thing left for kernel threads, which have an empty cmdline.
    val command = readProcFile("/proc/$pid/cmdline")
        ?.split(NUL)
        ?.filter { it.isNotBlank() }
        ?.joinToString(" ")
        ?.takeIf { it.isNotBlank() }
        ?: readProcFile("/proc/$pid/comm")?.trim()?.takeIf { it.isNotBlank() }
        ?: return null

    return ProcessInfo(pid = pid, command = command, uptime = processUptime(pid))
}

private fun processUptime(pid: Int): Duration? {
    val stat = readProcFile("/proc/$pid/stat") ?: return null
    // The comm field is wrapped in parentheses and may itself contain spaces and parentheses, so the
    // fields are counted from behind the last ')': starttime is field 22, the 20th one after it.
    val startTicks = stat.substringAfterLast(')')
        .trim()
        .split(' ')
        .getOrNull(19)
        ?.toLongOrNull()
        ?: return null
    val ticksPerSecond = sysconf(_SC_CLK_TCK).takeIf { it > 0 } ?: return null
    val systemUptime = readProcFile("/proc/uptime")?.substringBefore(' ')?.toDoubleOrNull() ?: return null
    return (systemUptime - startTicks.toDouble() / ticksPerSecond).takeIf { it >= 0 }?.seconds
}

/**
 * `/proc` files report a size of zero, so they have to be read into a fixed buffer instead of one
 * sized from the file itself.
 */
@OptIn(ExperimentalForeignApi::class)
private fun readProcFile(path: String): String? {
    val file = fopen(path, "r") ?: return null
    try {
        val buffer = ByteArray(8 * 1024)
        buffer.usePinned { pinned ->
            val read = fread(pinned.addressOf(0), 1u, buffer.size.toULong(), file).toInt()
            if (read <= 0) return null
            return buffer.decodeToString(0, read)
        }
    } finally {
        fclose(file)
    }
}
