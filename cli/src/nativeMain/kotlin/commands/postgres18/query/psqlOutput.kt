package commands.postgres18.query

import util.buildStyledString

private const val VERTICAL = '│'
private val BOX_CHARS = "─│┌┐└┘├┤┬┴┼".toSet()
private val RECORD_HEADER = Regex("""\[ RECORD \d+ ]""")
private val ROW_COUNT_FOOTER = Regex("""^\(\d+ rows?\)$""")
private val COMMAND_TAG = Regex("""^[A-Z]+( [A-Z]+)*( \d+)*$""")
internal const val NULL_PLACEHOLDER = "NULL"

/** Which cells of a table row hold column names instead of values. */
private enum class ColumnNamesIn {
    /** Non-expanded layout: the whole header row. */
    ALL_CELLS,

    /** Expanded layout: the label column of every record line. */
    FIRST_CELL,
    NONE,
}

/**
 * Colorizes the output psql produced for a query: borders are dimmed, column names highlighted,
 * NULLs dimmed so they can be told apart from empty strings, and footers/timings dimmed.
 * Works for both the aligned and the expanded (`\x`) layout.
 */
fun styleQueryOutput(output: String): String {
    val lines = output.trimEnd('\n').lines()
    val expanded = lines.any { RECORD_HEADER.containsMatchIn(it) }
    var headerPending = !expanded

    return lines.joinToString("\n") { line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> line
            VERTICAL in line -> {
                val columnNames = when {
                    expanded -> ColumnNamesIn.FIRST_CELL
                    headerPending -> ColumnNamesIn.ALL_CELLS
                    else -> ColumnNamesIn.NONE
                }
                headerPending = false
                styleRow(line, columnNames)
            }
            RECORD_HEADER.containsMatchIn(line) -> styleBorder(line)
            line.all { it in BOX_CHARS || it == ' ' } -> buildStyledString { gray { +line } }
            ROW_COUNT_FOOTER.matches(trimmed) || trimmed.startsWith("Time: ") -> buildStyledString { gray { +line } }
            COMMAND_TAG.matches(trimmed) -> buildStyledString { green { +line } }
            else -> line
        }
    }
}

/** Colorizes the psql diagnostics of a failed or noisy query (stderr). */
fun styleQueryError(error: String): String = error.trimEnd('\n').lines().joinToString("\n") { line ->
    when {
        "ERROR:" in line || "FATAL:" in line || "PANIC:" in line -> buildStyledString { red { bold { +line } } }
        "WARNING:" in line || "NOTICE:" in line || "INFO:" in line -> buildStyledString { yellow { +line } }
        else -> buildStyledString { gray { +line } }
    }
}

private fun styleRow(line: String, columnNames: ColumnNamesIn): String = buildStyledString {
    line.split(VERTICAL).forEachIndexed { index, cell ->
        if (index > 0) gray { +VERTICAL.toString() }
        val isColumnName = when (columnNames) {
            ColumnNamesIn.ALL_CELLS -> true
            ColumnNamesIn.FIRST_CELL -> index == 1
            ColumnNamesIn.NONE -> false
        }
        when {
            cell.isEmpty() -> Unit
            isColumnName -> cyan { bold { +cell } }
            cell.trim() == NULL_PLACEHOLDER -> gray { +cell }
            else -> +cell
        }
    }
}

/** Dims the box drawing characters of a border line and highlights labels like `[ RECORD 1 ]` on it. */
private fun styleBorder(line: String): String = buildStyledString {
    var index = 0
    while (index < line.length) {
        val isBox = line[index] in BOX_CHARS
        var end = index
        while (end < line.length && (line[end] in BOX_CHARS) == isBox) end++
        val run = line.substring(index, end)
        if (isBox) gray { +run } else cyan { +run }
        index = end
    }
}
