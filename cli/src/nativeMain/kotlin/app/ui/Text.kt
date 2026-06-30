package app.ui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.Measurable
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.layout.MeasureScope
import com.jakewharton.mosaic.layout.MeasureResult
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle
import com.jakewharton.mosaic.ui.unit.Constraints

enum class TextOverflow {
	Clip,
	Ellipsis,
}

@Composable
fun Text(
	value: String,
	modifier: Modifier = Modifier,
	minLines: Int = 1,
	maxLines: Int = Int.MAX_VALUE,
	overflow: TextOverflow = TextOverflow.Ellipsis,
	color: Color = Color.Unspecified,
	background: Color = Color.Unspecified,
	textStyle: TextStyle = TextStyle.Unspecified,
	underlineStyle: UnderlineStyle = UnderlineStyle.Unspecified,
	underlineColor: Color = Color.Unspecified,
) {
	Layout(
		content = {},
		modifier = modifier.drawBehind {
			if (width <= 0 || height <= 0) return@drawBehind
			val drawMaxLines = maxLines.coerceAtMost(height.coerceAtLeast(0))
			val display = truncateText(value, width, minLines, drawMaxLines, overflow)
			val lineList = display.lines().take(height.coerceAtLeast(0))
			lineList.forEachIndexed { row, line ->
				if (line.isNotEmpty()) {
					drawText(
						row = row,
						column = 0,
						string = line,
						foreground = color,
						background = background,
						textStyle = textStyle,
						underlineStyle = underlineStyle,
						underlineColor = underlineColor,
					)
				}
			}
		},
		measurePolicy = MeasurePolicy { _, constraints: Constraints ->
			val availableWidth = constraints.maxWidth
			val availableHeight = constraints.maxHeight
			val heightLimited = availableHeight != Constraints.Infinity
			val effectiveMaxLines = if (heightLimited) {
				maxLines.coerceAtMost(availableHeight)
			} else {
				maxLines
			}
			val effectiveMinLines = minLines.coerceAtMost(effectiveMaxLines.coerceAtLeast(0))
			if (effectiveMaxLines <= 0) {
				return@MeasurePolicy layout(0, 0) {}
			}
			val display = truncateText(value, availableWidth.coerceAtLeast(0), effectiveMinLines, effectiveMaxLines, overflow)
			val lines = display.lines()
			val w = lines.maxOfOrNull { it.length } ?: 0
			val h = lines.size.coerceIn(effectiveMinLines, effectiveMaxLines.coerceAtLeast(0))
			layout(w, h) {}
		},
	)
}

private fun truncateText(
	value: String,
	availableWidth: Int,
	minLines: Int,
	maxLines: Int,
	overflow: TextOverflow,
): String {
	if (availableWidth <= 0) return ""

	val rawLines = value.lines()
	val hasOverflowLines = rawLines.size > maxLines
	val shownLines = rawLines.take(maxLines)

	val result = mutableListOf<String>()

	shownLines.forEachIndexed { i, line ->
		val isLast = i == shownLines.lastIndex
		val needsWidthTruncation = line.length > availableWidth
		val needsEllipsisSuffix = overflow == TextOverflow.Ellipsis && isLast && (needsWidthTruncation || hasOverflowLines)

		val displayLine = when {
			needsEllipsisSuffix -> line.take((availableWidth - 3).coerceAtLeast(0)) + "..."
			overflow == TextOverflow.Clip && needsWidthTruncation -> line.take(availableWidth)
			else -> line
		}
		result.add(displayLine)
	}

	while (result.size < minLines) {
		result.add("")
	}

	return result.joinToString("\n")
}
