package app.werkbank.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes [content] to a temp file, runs [block] with it and deletes it afterwards, also on failure
 * or cancellation. For external tools that only accept a path. Not for secrets: the file lives on
 * disk for the duration of [block] with default temp permissions.
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
