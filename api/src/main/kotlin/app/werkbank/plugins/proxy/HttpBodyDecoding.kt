package app.werkbank.plugins.proxy

import io.airlift.compress.zstd.ZstdInputStream
import org.brotli.dec.BrotliInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** A stored HTTP body stream together with whether a Content-Encoding was actually undone. */
data class DecodedBody(val stream: InputStream, val decoded: Boolean)

/**
 * Wraps [source] so a body compressed with [contentEncoding] is transparently decompressed while
 * being read. [DecodedBody.decoded] is true only when a supported encoding was actually applied,
 * so callers know to drop the now-stale Content-Encoding/Content-Length headers.
 *
 * Supported: gzip (+ x-gzip), deflate (zlib- or raw-wrapped), br (brotli), zstd. All decoders are
 * pure-JVM (no JNI), so they are safe on our musl/Alpine runtime. "identity"/absent and any other
 * encoding are passed through untouched.
 */
fun decodeHttpBody(source: InputStream, contentEncoding: String?): DecodedBody {
    // A chain like "gzip, br" is applied left-to-right, so the last token is the outermost layer.
    val encoding = contentEncoding?.substringAfterLast(',')?.trim()?.lowercase()
    return when (encoding) {
        "gzip", "x-gzip" -> DecodedBody(GZIPInputStream(source), true)
        "deflate" -> DecodedBody(inflate(source), true)
        "br" -> DecodedBody(BrotliInputStream(source), true)
        // aircompressor's pure-Java zstd decoder (Caddy compresses with zstd, which zstd-jni can't
        // decode on Alpine because it only ships glibc-linked natives).
        "zstd" -> DecodedBody(ZstdInputStream(source), true)
        else -> DecodedBody(source, false)
    }
}

/** Picks the Content-Encoding header value case-insensitively, ignoring "identity". */
fun Map<String, List<String>>.contentEncoding(): String? =
    entries.firstOrNull { it.key.equals("Content-Encoding", ignoreCase = true) }
        ?.value?.firstOrNull()
        ?.takeUnless { it.equals("identity", ignoreCase = true) }

/** Drops headers that no longer describe the body once it has been decompressed. */
fun Map<String, List<String>>.withoutBodyEncodingHeaders(): Map<String, List<String>> =
    filterKeys {
        !it.equals("Content-Encoding", ignoreCase = true) && !it.equals("Content-Length", ignoreCase = true)
    }

/**
 * HTTP "deflate" is officially zlib-wrapped (RFC 1950) but many servers emit raw DEFLATE
 * (RFC 1951). Sniff the two-byte zlib header to pick the right [Inflater] mode.
 */
private fun inflate(source: InputStream): InputStream {
    val buffered = BufferedInputStream(source)
    buffered.mark(2)
    val b0 = buffered.read()
    val b1 = buffered.read()
    buffered.reset()
    // zlib: low nibble of first byte is CM=8 (deflate) and the 16-bit header is a multiple of 31.
    val looksZlib = b0 != -1 && b1 != -1 &&
        (b0 and 0x0f) == 0x08 &&
        (((b0 shl 8) or b1) % 31) == 0
    return InflaterInputStream(buffered, Inflater(/* nowrap = */ !looksZlib))
}
