package commands.setup

/**
 * Minimal, dependency-free ICO -> PNG converter for Kotlin/Native.
 *
 * An ICO file embeds one or more images. Each image is stored either as a PNG
 * (which we extract as-is) or as a Windows DIB / BMP (which we decode to RGBA
 * and re-encode as PNG). Only the two common DIB layouts, 24-bit BGR and
 * 32-bit BGRA, are supported. Returns `null` if the input cannot be converted.
 *
 * The PNG encoder writes uncompressed ("stored") DEFLATE blocks wrapped in a
 * zlib stream, so no compression library is required.
 */
object IcoToPngConverter {

    private val pngSignature = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun convert(ico: ByteArray): ByteArray? {
        val entry = selectBestEntry(ico) ?: return null
        val end = minOf(entry.offset + entry.size, ico.size)
        if (end <= entry.offset) return null
        val imageData = ico.copyOfRange(entry.offset, end)

        if (imageData.startsWithSignature(pngSignature)) return imageData

        val decoded = decodeDib(imageData) ?: return null
        return encodePng(decoded.width, decoded.height, decoded.pixels)
    }

    // --- ICO directory parsing -------------------------------------------------

    private class Entry(val width: Int, val height: Int, val bitCount: Int, val size: Int, val offset: Int) {
        val area: Int get() = width * height
    }

    private fun selectBestEntry(ico: ByteArray): Entry? {
        if (ico.size < 6) return null
        if (ico.u16le(2) != 1) return null // type 1 = icon
        val count = ico.u16le(4)
        if (count <= 0) return null

        var best: Entry? = null
        for (i in 0 until count) {
            val base = 6 + i * 16
            if (base + 16 > ico.size) break
            val width = ico.u8(base).let { if (it == 0) 256 else it }
            val height = ico.u8(base + 1).let { if (it == 0) 256 else it }
            val bitCount = ico.u16le(base + 6)
            val size = readInt32(ico, base + 8)
            val offset = readInt32(ico, base + 12)
            if (size <= 0 || offset < 0 || offset >= ico.size) continue

            val entry = Entry(width, height, bitCount, size, offset)
            val current = best
            if (current == null ||
                entry.area > current.area ||
                (entry.area == current.area && entry.bitCount > current.bitCount)
            ) {
                best = entry
            }
        }
        return best
    }

    // --- DIB (BMP) decoding ----------------------------------------------------

    private class Rgba(val width: Int, val height: Int, val pixels: ByteArray)

    private fun decodeDib(data: ByteArray): Rgba? {
        if (data.size < 40) return null
        val headerSize = readInt32(data, 0)
        if (headerSize < 40) return null

        val width = readInt32(data, 4)
        val heightField = readInt32(data, 8)
        val bitCount = data.u16le(14)
        val compression = readInt32(data, 16)

        // Only uncompressed 24/32-bit true-color images are supported.
        if (compression != 0) return null
        if (bitCount != 24 && bitCount != 32) return null
        if (width <= 0 || width > 4096 || heightField <= 0) return null

        // In ICO DIBs the stored height covers the XOR image plus the AND mask.
        val height = heightField / 2
        if (height <= 0 || height > 4096) return null

        val rowSize = if (bitCount == 32) width * 4 else ((width * 3 + 3) / 4) * 4
        val pixelOffset = headerSize
        val pixelDataSize = rowSize * height
        if (pixelOffset + pixelDataSize > data.size) return null

        val out = ByteArray(width * height * 4)
        var alphaAllZero = true

        for (y in 0 until height) {
            val srcRow = height - 1 - y // DIB rows are stored bottom-up
            val rowStart = pixelOffset + srcRow * rowSize
            for (x in 0 until width) {
                val di = (y * width + x) * 4
                if (bitCount == 32) {
                    val p = rowStart + x * 4
                    out[di] = data[p + 2]     // R
                    out[di + 1] = data[p + 1] // G
                    out[di + 2] = data[p]     // B
                    val a = data.u8(p + 3)
                    out[di + 3] = a.toByte()  // A
                    if (a != 0) alphaAllZero = false
                } else {
                    val p = rowStart + x * 3
                    out[di] = data[p + 2]     // R
                    out[di + 1] = data[p + 1] // G
                    out[di + 2] = data[p]     // B
                    out[di + 3] = 255.toByte()
                }
            }
        }

        // Apply the 1-bit AND mask when there is no usable alpha channel.
        val maskRowSize = ((width + 31) / 32) * 4
        val maskOffset = pixelOffset + pixelDataSize
        val maskAvailable = maskRowSize > 0 && maskOffset + maskRowSize * height <= data.size
        val useMask = bitCount == 24 || alphaAllZero
        if (useMask && maskAvailable) {
            for (y in 0 until height) {
                val srcRow = height - 1 - y
                val maskRowStart = maskOffset + srcRow * maskRowSize
                for (x in 0 until width) {
                    val bit = (data.u8(maskRowStart + x / 8) shr (7 - x % 8)) and 1
                    out[(y * width + x) * 4 + 3] = if (bit == 1) 0 else 255.toByte()
                }
            }
        } else if (bitCount == 32 && alphaAllZero) {
            // 32-bit image with an all-zero alpha channel and no mask: treat as opaque.
            for (i in 0 until width * height) out[i * 4 + 3] = 255.toByte()
        }

        return Rgba(width, height, out)
    }

    // --- PNG encoding ----------------------------------------------------------

    private fun encodePng(width: Int, height: Int, rgba: ByteArray): ByteArray {
        val ihdr = ByteArray(13)
        writeIntBE(ihdr, 0, width)
        writeIntBE(ihdr, 4, height)
        ihdr[8] = 8   // bit depth
        ihdr[9] = 6   // color type: RGBA
        ihdr[10] = 0  // compression
        ihdr[11] = 0  // filter
        ihdr[12] = 0  // interlace

        val stride = width * 4
        val raw = ByteArray(height * (1 + stride))
        for (y in 0 until height) {
            val ri = y * (1 + stride)
            raw[ri] = 0 // filter type: none
            rgba.copyInto(raw, ri + 1, y * stride, y * stride + stride)
        }

        return concat(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            chunk("IHDR", ihdr),
            chunk("IDAT", zlibStore(raw)),
            chunk("IEND", ByteArray(0)),
        )
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.encodeToByteArray()
        val out = ByteArray(4 + typeBytes.size + data.size + 4)
        writeIntBE(out, 0, data.size)
        typeBytes.copyInto(out, 4)
        data.copyInto(out, 4 + typeBytes.size)

        val crcInput = ByteArray(typeBytes.size + data.size)
        typeBytes.copyInto(crcInput, 0)
        data.copyInto(crcInput, typeBytes.size)
        writeIntBE(out, 4 + typeBytes.size + data.size, crc32(crcInput))
        return out
    }

    /** Wraps [data] in a zlib stream using uncompressed DEFLATE blocks. */
    private fun zlibStore(data: ByteArray): ByteArray {
        val maxBlock = 0xFFFF
        val numBlocks = maxOf(1, (data.size + maxBlock - 1) / maxBlock)
        val out = ByteArray(2 + numBlocks * 5 + data.size + 4)
        var pos = 0

        out[pos++] = 0x78.toByte() // zlib header (CMF)
        out[pos++] = 0x01.toByte() // zlib header (FLG, no compression)

        var i = 0
        for (b in 0 until numBlocks) {
            val len = minOf(maxBlock, data.size - i)
            out[pos++] = (if (b == numBlocks - 1) 1 else 0).toByte() // BFINAL + BTYPE=00 (stored)
            out[pos++] = (len and 0xFF).toByte()
            out[pos++] = ((len shr 8) and 0xFF).toByte()
            val nlen = len.inv() and 0xFFFF
            out[pos++] = (nlen and 0xFF).toByte()
            out[pos++] = ((nlen shr 8) and 0xFF).toByte()
            data.copyInto(out, pos, i, i + len)
            pos += len
            i += len
        }

        writeIntBE(out, pos, adler32(data))
        return out
    }

    // --- checksums -------------------------------------------------------------

    private val crcTable = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

    private fun crc32(data: ByteArray): Int {
        var c = -1 // 0xFFFFFFFF
        for (byte in data) c = crcTable[(c xor byte.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    private fun adler32(data: ByteArray): Int {
        var a = 1L
        var b = 0L
        val mod = 65521L
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % mod
            b = (b + a) % mod
        }
        return ((b shl 16) or a).toInt()
    }

    // --- byte helpers ----------------------------------------------------------

    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
    private fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

    private fun readInt32(data: ByteArray, i: Int): Int =
        (data[i].toInt() and 0xFF) or
            ((data[i + 1].toInt() and 0xFF) shl 8) or
            ((data[i + 2].toInt() and 0xFF) shl 16) or
            ((data[i + 3].toInt() and 0xFF) shl 24)

    private fun writeIntBE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun ByteArray.startsWithSignature(signature: IntArray): Boolean {
        if (size < signature.size) return false
        for (i in signature.indices) if (u8(i) != signature[i]) return false
        return true
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArray(arrays.sumOf { it.size })
        var pos = 0
        for (a in arrays) {
            a.copyInto(out, pos)
            pos += a.size
        }
        return out
    }
}
