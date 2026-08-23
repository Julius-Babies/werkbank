package app.process

import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * macOS keeps this behind `sysctl`, so `ps` does the digging: it is the only way to the full command
 * line without cinterop into `libproc`.
 */
actual fun processInfo(pid: Int): ProcessInfo? {
    // The elapsed time comes first because the command is the one field that contains spaces.
    // `ps` exits with 1 for an unknown pid, so the status is not checked, only the output.
    val output = runCatching {
        Command("ps")
            .args("-p", pid.toString(), "-o", "etime=,command=")
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Null)
            .spawn()
            .waitWithOutput()
    }.getOrNull() ?: return null

    val line = output.stdout?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
    val command = line.substringAfter(' ').trim().takeIf { it.isNotEmpty() } ?: return null
    return ProcessInfo(pid = pid, command = command, uptime = parseElapsedTime(line.substringBefore(' ')))
}

/** `ps` prints the elapsed time as `[[dd-]hh:]mm:ss`. */
private fun parseElapsedTime(value: String): Duration? {
    val days = value.substringBefore('-', "").toLongOrNull() ?: 0
    val clock = value.substringAfter('-').split(':').map { it.toLongOrNull() ?: return null }
    val (hours, minutes, seconds) = when (clock.size) {
        3 -> Triple(clock[0], clock[1], clock[2])
        2 -> Triple(0L, clock[0], clock[1])
        else -> return null
    }
    return days.days + hours.hours + minutes.minutes + seconds.seconds
}
