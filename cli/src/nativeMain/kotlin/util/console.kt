@file:Suppress("unused")

package util

const val REPLACE_LINE = "\r\u001B[2K"

const val STAR = "★"
const val WHITE_STAR = "☆"
const val CHECK_MARK = "✓"
const val HEAVY_CHECK_MARK = "✔"
const val CROSS_MARK = "✗"
const val HEAVY_CROSS_MARK = "✘"
const val INFO = "ℹ"
const val LIGHTNING = "⚡"
const val RIGHT_ARROW = "→"
const val LEFT_ARROW = "←"
const val UP_ARROW = "↑"
const val DOWN_ARROW = "↓"
const val DOUBLE_EXCLAMATION = "‼"
const val BLACK_CIRCLE = "●"
const val WHITE_CIRCLE = "○"
const val SQUARE = "■"
const val WHITE_SQUARE = "□"
const val DIAMOND = "◆"
const val WHITE_DIAMOND = "◇"
const val BULLET = "•"
const val TRIANGULAR_BULLET = "‣"
const val HOUR_GLASS = "⧖"
const val WARNING = "⚠"
const val CHECK = "✓"
const val BALLOT_X = "✗"
const val HEAVY_BALLOT_X = "✘"

class ConsoleStyle(
    private var styles: List<String> = emptyList()
) {
    val parts = mutableListOf<Part>()

    sealed class Part {
        data class Styled(val style: ConsoleStyle): Part()
        data class Text(val text: String): Part()
    }

    operator fun String.unaryPlus() {
        parts.add(Part.Text(this))
    }

    fun black(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BLACK).apply(block)))
    }

    fun bgBlack(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BG_BLACK).apply(block)))
    }

    fun bgRed(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BG_RED).apply(block)))
    }

    fun bgGreen(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BG_GREEN).apply(block)))
    }

    fun bgYellow(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BG_YELLOW).apply(block)))
    }

    fun bgBlue(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BG_BLUE).apply(block)))
    }

    fun bgPurple(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BG_PURPLE).apply(block)))
    }

    fun bgCyan(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BG_CYAN).apply(block)))
    }

    fun bgWhite(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BG_WHITE).apply(block)))
    }

    fun bgGray(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BG_GRAY).apply(block)))
    }

    fun aqua(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + AQUA).apply(block)))
    }

    fun red(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + RED).apply(block)))
    }

    fun green(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + GREEN).apply(block)))
    }

    fun yellow(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + YELLOW).apply(block)))
    }

    fun blue(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BLUE).apply(block)))
    }

    fun purple(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + PURPLE).apply(block)))
    }

    fun cyan(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + CYAN).apply(block)))
    }

    fun white(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + WHITE).apply(block)))
    }

    fun gray(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + GRAY).apply(block)))
    }

    fun bold(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + BOLD).apply(block)))
    }

    fun italic(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + ITALIC).apply(block)))
    }

    fun underline(block: ConsoleStyle.() -> Unit) {
        parts.add(Part.Styled(ConsoleStyle(this@ConsoleStyle.styles + UNDERLINE).apply(block)))
    }

    companion object {
        const val RESET = "\u001B[0m"
        const val BLACK = "\u001B[30m"
        const val AQUA = "\u001B[33m"
        const val RED = "\u001B[31m"
        const val GREEN = "\u001B[32m"
        const val YELLOW = "\u001B[33m"
        const val BLUE = "\u001B[34m"
        const val PURPLE = "\u001B[35m"
        const val CYAN = "\u001B[36m"
        const val WHITE = "\u001B[37m"
        const val GRAY = "\u001B[90m"
        const val BG_BLACK = "\u001B[40m"
        const val BG_RED = "\u001B[41m"
        const val BG_GREEN = "\u001B[42m"
        const val BG_YELLOW = "\u001B[43m"
        const val BG_BLUE = "\u001B[44m"
        const val BG_PURPLE = "\u001B[45m"
        const val BG_CYAN = "\u001B[46m"
        const val BG_WHITE = "\u001B[47m"
        const val BG_GRAY = "\u001B[100m"
        const val BOLD = "\u001B[1m"
        const val ITALIC = "\u001B[3m"
        const val UNDERLINE = "\u001B[4m"
    }

    override fun toString(): String {
        return buildString {
            this@ConsoleStyle.styles.lastOrNull()?.let { append(it) }
            parts.forEach { part ->
                when (part) {
                    is Part.Styled -> {
                        append(part.style)
                        append(RESET)
                        append(this@ConsoleStyle.styles.joinToString(""))
                    }
                    is Part.Text -> append(part.text)
                }
            }
        }
    }
}

inline fun buildStyledString(block: ConsoleStyle.() -> Unit): String = ConsoleStyle().apply(block).toString()