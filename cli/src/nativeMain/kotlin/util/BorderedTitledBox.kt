package util

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.offset
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Alignment
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text

/** [BorderedTitledBox] with a plain [title] on its top border. */
@Composable
fun BorderedTitledBox(
	title: String,
	titleColor: Color = Color.Cyan,
	borderColor: Color = Color.Cyan,
	modifier: Modifier = Modifier,
	borderVariant: Border.Variant = Border.Variant.Hard,
	content: @Composable BoxScope.() -> Unit,
) {
	BorderedTitledBox(
		title = { Text(title, color = titleColor) },
		borderColor = borderColor,
		modifier = modifier,
		borderVariant = borderVariant,
		content = content,
	)
}

/** A box with a border around [content] and [title] sitting on its top line. */
@Composable
fun BorderedTitledBox(
	title: @Composable () -> Unit,
	borderColor: Color = Color.Cyan,
	modifier: Modifier = Modifier,
	borderVariant: Border.Variant = Border.Variant.Hard,
	content: @Composable BoxScope.() -> Unit,
) {
	Box(
		modifier = modifier
			.border(
				topStart = borderVariant.topLeft.asTopStart(),
				topEnd = borderVariant.topRight.asTopEnd(),
				bottomStart = borderVariant.bottomLeft.asBottomStart(),
				bottomEnd = borderVariant.bottomRight.asBottomEnd(),
				color = borderColor,
			)
			.padding(horizontal = 1),
	) {
		// The title sits on the top border, so it closes it on its left and reopens it on its right.
		Row(modifier = Modifier.align(Alignment.TopStart).offset(x = -1, y = -1)) {
			Text("${borderVariant.titleLeft.asTopEnd()} ", color = borderColor)
			title()
			Text(" ${borderVariant.titleRight.asTopStart()}", color = borderColor)
		}
		content()
	}
}

private fun Border.Corner.asTopStart() = when (this) {
	Border.Corner.Hard -> '┌'
	Border.Corner.Rounded -> '╭'
}

private fun Border.Corner.asTopEnd() = when (this) {
	Border.Corner.Hard -> '┐'
	Border.Corner.Rounded -> '╮'
}

private fun Border.Corner.asBottomStart() = when (this) {
	Border.Corner.Hard -> '└'
	Border.Corner.Rounded -> '╰'
}

private fun Border.Corner.asBottomEnd() = when (this) {
	Border.Corner.Hard -> '┘'
	Border.Corner.Rounded -> '╯'
}

class Border {
	enum class Corner {
		Hard, Rounded
	}

	interface Box {
		val topLeft: Corner
		val topRight: Corner
		val bottomLeft: Corner
		val bottomRight: Corner
		val titleLeft: Corner get() = topLeft
		val titleRight: Corner get() = topRight
	}

	sealed class Variant : Box {
		data object Hard : Variant() {
			override val topLeft = Corner.Hard
			override val topRight = Corner.Hard
			override val bottomLeft = Corner.Hard
			override val bottomRight = Corner.Hard
		}

		data object Rounded : Variant() {
			override val topLeft = Corner.Rounded
			override val topRight = Corner.Rounded
			override val bottomLeft = Corner.Rounded
			override val bottomRight = Corner.Rounded
		}

		data class Custom(
			override val topLeft: Corner,
			override val topRight: Corner,
			override val bottomLeft: Corner,
			override val bottomRight: Corner,
			override val titleLeft: Corner = topLeft,
			override val titleRight: Corner = titleLeft,
		) : Variant()
	}
}