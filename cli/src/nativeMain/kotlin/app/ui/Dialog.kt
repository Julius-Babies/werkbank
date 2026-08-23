package app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.fillMaxSize
import com.jakewharton.mosaic.layout.offset
import com.jakewharton.mosaic.layout.onPreviewKeyEvent
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Alignment
import com.jakewharton.mosaic.ui.Arrangement
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Filler
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.TextStyle

@Immutable
data class DialogButton(
	val label: String,
	val onActivate: () -> Unit,
)

/**
 * A modal dialog centered on the terminal. It grows with its content up to [MAX_DIALOG_WIDTH] and
 * the terminal size; on a small terminal it simply ends up filling the screen.
 *
 * Keys: `Escape` calls [onDismiss], `Tab`/`Shift+Tab` cycle through [buttons], `Enter` activates the
 * focused one and the arrow keys scroll [description]. Every other key is left alone so that e.g.
 * `Ctrl+C` still reaches mosaic.
 *
 * Show it as the *last* child of a full screen `Box` so it is drawn over the rest of the UI. Mosaic
 * dispatches keys to the children in order, so while this is visible the UI underneath has to ignore
 * the keys it handles itself.
 *
 * @param hideBackground blanks the whole screen behind the dialog instead of letting the UI below
 *   show around it.
 * @param scrollState hoist this to control or reset the scroll position of [description].
 */
@Composable
fun Dialog(
	title: String,
	description: String,
	buttons: List<DialogButton>,
	onDismiss: () -> Unit,
	hideBackground: Boolean = false,
	scrollState: ScrollState = rememberScrollState(),
) {
	val terminal = LocalTerminalState.current
	var focusedIndex by remember { mutableIntStateOf(0) }
	// The button list can change while the dialog is open; never point past its end.
	val focused = if (buttons.isEmpty()) 0 else focusedIndex.coerceIn(0, buttons.lastIndex)

	val maxTextWidth = (minOf(MAX_DIALOG_WIDTH, terminal.size.columns) - HORIZONTAL_CHROME)
		.coerceAtLeast(1)
	val lines = remember(description, maxTextWidth) { wrapText(description, maxTextWidth) }

	val buttonsWidth = buttons.sumOf { it.label.length + BUTTON_PADDING } +
		(buttons.size - 1).coerceAtLeast(0) * BUTTON_GAP
	val contentWidth = maxOf(
		lines.maxOfOrNull { it.length } ?: 0,
		title.length + 2,
		buttonsWidth,
	).coerceIn(1, maxTextWidth)

	// The separator is the first thing to go on a terminal that cannot fit border, text, separator
	// and buttons; the buttons themselves stay, because they are what the dialog is asking for.
	val hasSeparator = buttons.isNotEmpty() && terminal.size.rows >= MIN_ROWS_FOR_SEPARATOR
	val verticalChrome = 2 + (if (buttons.isEmpty()) 0 else 1) + (if (hasSeparator) 1 else 0)
	val bodyHeight = lines.size
		.coerceIn(1, (terminal.size.rows - verticalChrome).coerceAtLeast(1))

	val dialogWidth = contentWidth + HORIZONTAL_CHROME
	val dialogHeight = bodyHeight + verticalChrome

	Box(
		modifier = Modifier
			// Sized to the terminal instead of to the parent so the dialog ends up centered on the
			// screen no matter how the host laid out the UI it covers.
			.size(terminal.size.columns, terminal.size.rows)
			.onPreviewKeyEvent { event ->
				when (event.key) {
					"Escape" -> {
						onDismiss()
						true
					}

					"Tab" -> {
						if (buttons.isNotEmpty()) {
							val step = if (event.shift) buttons.size - 1 else 1
							focusedIndex = (focused + step) % buttons.size
						}
						true
					}

					"Enter" -> {
						buttons.getOrNull(focused)?.onActivate()
						true
					}

					"ArrowUp" -> {
						scrollState.scrollBy(-1)
						true
					}

					"ArrowDown" -> {
						scrollState.scrollBy(1)
						true
					}

					else -> false
				}
			},
		contentAlignment = Alignment.Center,
	) {
		if (hideBackground) Filler(' ', Modifier.fillMaxSize())

		Box(Modifier.size(dialogWidth, dialogHeight)) {
			// Blanks out whatever the dialog covers; a background color alone would keep the
			// characters underneath visible.
			Filler(' ', Modifier.fillMaxSize())

			// The body comes first because scrolling paints the rows above the viewport over the top
			// border (mosaic has no clipping, see [Modifier.verticalScrollable]). Scrolling is only
			// possible when the dialog is as tall as the terminal, so nothing can escape past row 0,
			// and the frame drawn after this covers what lands on the border itself.
			Box(Modifier.offset(x = BORDER + PADDING, y = 1).size(contentWidth, bodyHeight)) {
				Text(
					value = lines.joinToString("\n"),
					modifier = Modifier.verticalScrollable(scrollState),
					overflow = TextOverflow.Clip,
				)
			}

			Spacer(
				Modifier
					.fillMaxSize()
					.drawBehind {
						drawText(0, 0, "╭" + "─".repeat(dialogWidth - 2) + "╮")
						for (row in 1 until dialogHeight - 1) {
							drawText(row, 0, "│")
							drawText(row, dialogWidth - 1, "│")
						}
						drawText(dialogHeight - 1, 0, "╰" + "─".repeat(dialogWidth - 2) + "╯")
						if (hasSeparator) {
							drawText(dialogHeight - 3, 0, "├" + "─".repeat(dialogWidth - 2) + "┤")
						}
						if (title.isNotEmpty() && contentWidth > 2) {
							drawText(
								row = 0,
								column = 2,
								// Keeps the closing corner of the border free on a narrow dialog.
								string = " " + title.take(contentWidth - 2) + " ",
								foreground = Color.Blue,
								textStyle = TextStyle.Bold,
							)
						}
						if (scrollState.canScrollUp) drawText(1, dialogWidth - 1, "▲")
						if (scrollState.canScrollDown) drawText(bodyHeight, dialogWidth - 1, "▼")
					},
			)

			if (buttons.isNotEmpty()) {
				Box(
					// Inside the borders, so that buttons too wide for the dialog are truncated
					// instead of painted over the frame.
					modifier = Modifier
						.offset(x = BORDER + PADDING, y = dialogHeight - 2)
						.width(contentWidth),
					contentAlignment = Alignment.Center,
				) {
					Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
						buttons.forEachIndexed { index, button ->
							Text(
								value = "[ ${button.label} ]",
								textStyle = if (index == focused) TextStyle.Invert else TextStyle.Unspecified,
							)
						}
					}
				}
			}
		}
	}
}

/** Widest a dialog gets before its text starts wrapping. */
private const val MAX_DIALOG_WIDTH = 64

private const val BORDER = 1
private const val PADDING = 1

/** Border and padding on both sides. */
private const val HORIZONTAL_CHROME = (BORDER + PADDING) * 2

/** Below this the dialog drops the line between text and buttons to keep a row of text. */
private const val MIN_ROWS_FOR_SEPARATOR = 5

/** The `[ ` and ` ]` around a button label. */
private const val BUTTON_PADDING = 4
private const val BUTTON_GAP = 2

/**
 * Breaks [text] into lines of at most [width] characters, keeping its existing line breaks. Words
 * that do not fit on a line of their own are broken up.
 */
private fun wrapText(text: String, width: Int): List<String> {
	if (width <= 0) return emptyList()

	val result = mutableListOf<String>()
	for (paragraph in text.lines()) {
		var line = StringBuilder()
		for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
			var rest = word
			while (rest.length > width) {
				if (line.isNotEmpty()) {
					result += line.toString()
					line = StringBuilder()
				}
				result += rest.take(width)
				rest = rest.drop(width)
			}
			if (rest.isEmpty()) continue
			when {
				line.isEmpty() -> line.append(rest)
				line.length + 1 + rest.length <= width -> line.append(' ').append(rest)
				else -> {
					result += line.toString()
					line = StringBuilder(rest)
				}
			}
		}
		result += line.toString()
	}
	return result
}
