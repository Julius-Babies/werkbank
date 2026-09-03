package util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.STDOUT_FILENO
import platform.posix.TIOCGWINSZ
import platform.posix.getenv
import platform.posix.ioctl
import platform.posix.isatty
import platform.posix.winsize

/** True if stdout is attached to a terminal, false if it is piped or redirected. */
@OptIn(ExperimentalForeignApi::class)
fun isTerminal(): Boolean = isatty(STDOUT_FILENO) == 1

/** Width of the terminal attached to stdout, or [fallback] if there is none. */
@OptIn(ExperimentalForeignApi::class)
fun terminalWidth(fallback: Int = 120): Int {
    memScoped {
        val size = alloc<winsize>()
        if (ioctl(STDOUT_FILENO, TIOCGWINSZ.convert(), size.ptr) == 0) {
            val columns = size.ws_col.toInt()
            if (columns > 0) return columns
        }
    }
    return getenv("COLUMNS")?.toKString()?.toIntOrNull()?.takeIf { it > 0 } ?: fallback
}
