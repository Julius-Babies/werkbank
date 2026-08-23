package app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.LayoutModifier
import com.jakewharton.mosaic.layout.Measurable
import com.jakewharton.mosaic.layout.MeasureResult
import com.jakewharton.mosaic.layout.MeasureScope
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.unit.Constraints

/**
 * Scroll position of a [Modifier.verticalScrollable] viewport. [contentHeight] and [viewportHeight]
 * are filled in while measuring, so [maxOffset] and the `canScroll*` flags only carry a meaningful
 * value once the viewport has been laid out once.
 */
@Stable
class ScrollState {

	/** Index of the first content row that is visible in the viewport. */
	var offset by mutableIntStateOf(0)
		private set

	/** Height of the whole content, measured without a height limit. */
	var contentHeight by mutableIntStateOf(0)
		private set

	/** Height of the visible window onto the content. */
	var viewportHeight by mutableIntStateOf(0)
		private set

	val maxOffset: Int get() = (contentHeight - viewportHeight).coerceAtLeast(0)
	val canScrollUp: Boolean get() = offset > 0
	val canScrollDown: Boolean get() = offset < maxOffset

	fun scrollBy(rows: Int) = scrollTo(offset + rows)

	fun scrollTo(row: Int) {
		offset = row.coerceIn(0, maxOffset)
	}

	internal fun onMeasured(contentHeight: Int, viewportHeight: Int) {
		this.contentHeight = contentHeight
		this.viewportHeight = viewportHeight
		// The content may have shrunk since the last frame, which can leave the offset past its end.
		if (offset > maxOffset) offset = maxOffset
	}
}

@Composable
fun rememberScrollState(): ScrollState = remember { ScrollState() }

/**
 * Turns the incoming height limit into a scrollable window onto the content: the content is measured
 * at its full height and then shifted up by [ScrollState.offset].
 *
 * Note that mosaic does not clip a child to its parent's bounds, so the rows scrolled *above* the
 * viewport are still painted, over whatever sits above it. Content below the viewport is never
 * measured and therefore cannot bleed out. Use this on a viewport whose top edge is either the top
 * of the screen or covered by something that is drawn afterwards.
 */
fun Modifier.verticalScrollable(state: ScrollState): Modifier = this then VerticalScrollableModifier(state)

private class VerticalScrollableModifier(
	private val state: ScrollState,
) : LayoutModifier {

	override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
		// The full height has to be known before the offset can be clamped, so measure once without
		// a limit. Measuring twice is cheap here: mosaic keeps only the result of the last pass.
		val contentHeight = measurable.measure(
			constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
		).height
		val viewportHeight = if (constraints.maxHeight == Constraints.Infinity) {
			contentHeight
		} else {
			constraints.maxHeight
		}
		state.onMeasured(contentHeight, viewportHeight)

		val offset = state.offset
		val placeable = measurable.measure(
			constraints.copy(minHeight = 0, maxHeight = offset + viewportHeight),
		)
		return layout(placeable.width, viewportHeight.coerceAtLeast(constraints.minHeight)) {
			placeable.place(0, -offset)
		}
	}

	override fun toString(): String = "VerticalScrollable(offset=${state.offset})"
}
