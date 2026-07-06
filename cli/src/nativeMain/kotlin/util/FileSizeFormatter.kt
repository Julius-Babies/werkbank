// stolen from Human-Readable library

package util

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Standalone file size formatter with no external dependencies.
 *
 * Drop this file into any Kotlin Multiplatform project and use:
 * ```
 * FileSizeFormatter.format(3_500_000) // "3.5 MB"
 * ```
 *
 * English-only formatting. Supports B, kB, MB, GB, TB.
 */
public object FileSizeFormatter {

    private val units = arrayOf("B", "kB", "MB", "GB", "TB")

    /**
     * Formats [bytes] to a human-readable file size string (base 1024).
     *
     * @param bytes   The size in bytes.
     * @param decimals Number of decimal places (default 1).
     */
    public fun format(bytes: Long, decimals: Int = 1): String {
        var size = bytes.toDouble()
        var unitIndex = 0

        while (size >= 1024 && unitIndex < units.lastIndex) {
            size /= 1024.0
            unitIndex++
        }

        return "${formatNumber(size, decimals)} ${units[unitIndex]}"
    }

    /**
     * Formats a Double with thousand separators and decimal places (English locale).
     * Example: 1234.5 with decimals=1 → "1,234.5"
     */
    private fun formatNumber(value: Double, decimals: Int): String {
        val multiplier = 10.0.pow(decimals)
        val rounded = (value * multiplier).roundToLong().toString().padStart(decimals + 1, '0')
        val decimalIndex = rounded.length - decimals - 1
        val mainPart = rounded.substring(0..decimalIndex)
        val fractionPart = rounded.substring(decimalIndex + 1)

        val withGrouping = mainPart
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()

        return if (decimals > 0) {
            val truncated = fractionPart.padEnd(decimals, '0').substring(0, decimals)
            "$withGrouping.$truncated"
        } else {
            withGrouping
        }
    }
}