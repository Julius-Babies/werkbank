package app.werkbank.shared.tunnel

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlin.io.encoding.Base64

suspend fun ByteReadChannel.base64Chunks(
    chunkSize: Int = 1024 * 1024,
    emit: suspend (String) -> Unit
) {
    val buffer = ByteArray(chunkSize)
    var remainder = ByteArray(0)

    while (!isClosedForRead) {
        val read = readAvailable(buffer)
        if (read <= 0) continue

        val combined = ByteArray(remainder.size + read)
        remainder.copyInto(combined)
        buffer.copyInto(combined, destinationOffset = remainder.size, endIndex = read)

        val encodableLength = (combined.size / 3) * 3
        val toEncode = combined.copyOfRange(0, encodableLength)

        remainder = combined.copyOfRange(encodableLength, combined.size)

        if (toEncode.isNotEmpty()) {
            emit(Base64.encode(toEncode))
        }
    }

    if (remainder.isNotEmpty()) {
        emit(Base64.encode(remainder))
    }
}