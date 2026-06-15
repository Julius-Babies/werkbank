package app.werkbank.shared.tunnel

import io.ktor.utils.io.*

suspend fun ByteReadChannel.rawChunks(
    chunkSize: Int = 64 * 1024,
    emit: suspend (ByteArray) -> Unit
) {
    val buffer = ByteArray(chunkSize)
    while (true) {
        val read = readAvailable(buffer)
        if (read <= 0) break
        emit(buffer.copyOfRange(0, read))
    }
}