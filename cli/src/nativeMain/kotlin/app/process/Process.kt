package app.process

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EPERM
import platform.posix.SIGTERM
import platform.posix.errno
import platform.posix.getpid
import platform.posix.kill
import kotlin.time.Duration

/** What the platform can tell us about a process this one did not start itself. */
data class ProcessInfo(
    val pid: Int,
    /** The command line, or just the executable name on platforms that report no more than that. */
    val command: String,
    /** How long the process has been running, if the platform reports it. */
    val uptime: Duration?,
) {
    /** The bare executable name, e.g. `wb` for `/usr/local/bin/wb tunnel`. */
    val executableName: String get() = command.trim().substringBefore(' ').substringAfterLast('/')
}

fun currentProcessId(): Int = getpid()

/** True while [pid] exists. A process of another user counts as running even though we cannot signal it. */
@OptIn(ExperimentalForeignApi::class)
fun isProcessRunning(pid: Int): Boolean {
    if (pid <= 0) return false
    if (kill(pid, 0) == 0) return true
    return errno == EPERM
}

/** Asks [pid] to shut down (SIGTERM). Returns false when the signal could not be delivered. */
fun stopProcess(pid: Int): Boolean = pid > 0 && kill(pid, SIGTERM) == 0

/**
 * Details about [pid], or `null` if it is gone or the platform will not tell us. Every platform has
 * its own way of asking — `/proc` on Linux, `ps` on macOS — hence the expect/actual.
 */
expect fun processInfo(pid: Int): ProcessInfo?
