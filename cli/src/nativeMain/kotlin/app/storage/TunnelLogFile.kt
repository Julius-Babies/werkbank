package app.storage

import es.jvbabi.kfile.File
import io.ktor.util.logging.LogLevel
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Append-only log of the tunnel's connection events in `~/.werkbank(dev)/tunnel.log`.
 *
 * The TUI only keeps the newest lines on screen, so this is where a failure that happened minutes
 * ago can still be looked up — errors are written with their full stack trace.
 */
object TunnelLogFile {

    /** Size at which the log is truncated, so a long-running tunnel cannot fill the disk. */
    private const val MAX_SIZE_BYTES = 5L * 1024 * 1024

    private val timestampFormat = LocalDateTime.Format {
        year(Padding.ZERO)
        char('-')
        monthNumber(Padding.ZERO)
        char('-')
        day(Padding.ZERO)
        char(' ')
        hour(Padding.ZERO)
        char(':')
        minute(Padding.ZERO)
        char(':')
        second(Padding.ZERO)
    }

    private val file: File get() = storageRoot.resolve("tunnel.log")

    val path: String get() = file.absolutePath

    fun append(timestamp: Instant, level: LogLevel, message: String, throwable: Throwable? = null) {
        val entry = buildString {
            append(timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).format(timestampFormat))
            append(' ')
            append(level.name.padEnd(5))
            append(' ')
            appendLine(message)
            if (throwable != null) appendLine(throwable.stackTraceToString().trimEnd())
        }
        runCatching {
            val file = file
            // Dropping the whole file instead of rotating it keeps this to a single write; the
            // interesting entries of a tunnel session are the recent ones anyway.
            if (file.exists() && file.size > MAX_SIZE_BYTES) file.writeText("")
            file.appendText(entry)
        }
    }
}
