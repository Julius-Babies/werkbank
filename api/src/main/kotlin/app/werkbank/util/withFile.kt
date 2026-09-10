package app.werkbank.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes [content] to a temp file, runs [block] with it and deletes it afterwards.
 *
 * For the external tools that only accept a path where we would rather pipe the data in. The file is
 * removed even if [block] throws or is cancelled, so nothing is left behind in the temp directory —
 * do not put secrets in it though, it exists on disk for the duration of [block] with the default
 * temp file permissions.
 */
suspend fun <T> withFile(
    content: String,
    prefix: String = "werkbank",
    suffix: String = ".tmp",
    block: suspend (File) -> T,
): T {
    val file = withContext(Dispatchers.IO) {
        File.createTempFile(prefix, suffix).apply { writeText(content) }
    }
    return try {
        block(file)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { file.delete() }
    }
}
