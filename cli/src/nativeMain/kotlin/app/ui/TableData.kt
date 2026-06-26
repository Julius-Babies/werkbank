package app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.fillMaxSize
import com.jakewharton.mosaic.layout.offset
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Spacer
import kotlin.math.roundToInt

@Immutable
sealed interface ColumnWidth {
	data class Weight(val weight: Int) : ColumnWidth
	data class Fixed(val width: Int) : ColumnWidth
}

@Immutable
data class TableData<T>(val items: List<T>)

@Immutable
data class TableConfig<T>(
	val titleColor: Color,
	val columnConfigs: List<ColumnConfig<T>>,
) {

	@Immutable
	sealed interface ColumnConfig<T> {
		val width: ColumnWidth

		@Immutable
		data class StringColumnConfig<T>(
			val title: String,
			val stringFromItem: (T) -> String,
			val valueColor: Color,
			val valueAlignment: ColumnAligment = ColumnAligment.START,
			override val width: ColumnWidth = ColumnWidth.Weight(1),
		) : ColumnConfig<T>

		@Immutable
		data class ProgressColumnConfig<T>(
			val filledColor: Color,
			val emptyColor: Color,
			val progressFromItem: (T) -> Float,
			override val width: ColumnWidth = ColumnWidth.Weight(1),
		) : ColumnConfig<T>

		@Immutable
		data class ComposableColumnConfig<T>(
			val title: String,
			val content: @Composable (T) -> Unit,
			override val width: ColumnWidth = ColumnWidth.Weight(1),
		) : ColumnConfig<T>

		enum class ColumnAligment {
			START,
			END,
		}
	}
}

@Composable
fun <T> Table(tableData: TableData<T>, tableConfig: TableConfig<T>, modifier: Modifier = Modifier) {
	var layoutWidth by remember { mutableIntStateOf(0) }
	var layoutHeight by remember { mutableIntStateOf(0) }

	fun List<TableConfig.ColumnConfig<*>>.computeWidths(totalWidth: Int): List<Int> {
		val fixedWidthsSum = sumOf {
			when (val w = it.width) {
				is ColumnWidth.Fixed -> w.width
				is ColumnWidth.Weight -> 0
			}
		}
		val totalWeights = sumOf {
			when (val w = it.width) {
				is ColumnWidth.Weight -> w.weight
				is ColumnWidth.Fixed -> 0
			}
		}
		val availableWidth = totalWidth - fixedWidthsSum - (size - 1)
		val widthSinglePart = if (totalWeights > 0) availableWidth / totalWeights else 0
		return map {
			when (val w = it.width) {
				is ColumnWidth.Fixed -> w.width
				is ColumnWidth.Weight -> w.weight * widthSinglePart
			}
		}
	}

	Box(
		modifier = modifier
			.drawBehind {
				layoutWidth = width
				layoutHeight = height

				val widths = tableConfig.columnConfigs.computeWidths(width)
				val lastRange = tableData.items.takeLast(height - 1).asReversed()

				var column = 0
				tableConfig.columnConfigs.forEachIndexed { columnIndex, columnConfig ->
					val columnWidth = widths[columnIndex]

					val title = when (columnConfig) {
						is TableConfig.ColumnConfig.StringColumnConfig -> columnConfig.title
						is TableConfig.ColumnConfig.ProgressColumnConfig -> ""
						is TableConfig.ColumnConfig.ComposableColumnConfig -> columnConfig.title
					}
					if (title.isNotEmpty()) {
						val displayTitle = if (title.length < columnWidth) title else title.substring(0, columnWidth)
						drawText(
							row = 0,
							column = column,
							string = displayTitle,
							foreground = tableConfig.titleColor,
						)
					}

					lastRange.forEachIndexed { index, item ->
						when (columnConfig) {
							is TableConfig.ColumnConfig.StringColumnConfig -> {
								val string = columnConfig.stringFromItem(item)
								val text = if (string.length < columnWidth) {
									string
								} else {
									string.substring(0, columnWidth)
								}
								drawText(
									row = index + 1,
									column = if (columnConfig.valueAlignment == TableConfig.ColumnConfig.ColumnAligment.START) {
										column
									} else {
										column + columnWidth - text.length
									},
									string = text,
									foreground = columnConfig.valueColor,
								)
							}

							is TableConfig.ColumnConfig.ProgressColumnConfig -> {
								drawText(
									row = index + 1,
									column = column,
									string = buildAnnotatedString {
										val progress = columnConfig.progressFromItem(item)
										val filledPart = (columnWidth * progress).roundToInt()
										withStyle(SpanStyle(columnConfig.filledColor)) {
											append("━".repeat(filledPart))
										}
										withStyle(SpanStyle(columnConfig.emptyColor)) {
											append("━".repeat(columnWidth - filledPart))
										}
									},
								)
							}

							is TableConfig.ColumnConfig.ComposableColumnConfig -> {
								// Rendered in composable scope below
							}
						}
					}
					column += columnWidth + 1
				}
			},
	) {
		if (layoutWidth > 0 && layoutHeight > 0) {
			val widths = tableConfig.columnConfigs.computeWidths(layoutWidth)
			val lastRange = tableData.items.takeLast(layoutHeight - 1).asReversed()

			var column = 0
			tableConfig.columnConfigs.forEachIndexed { columnIndex, columnConfig ->
				val columnWidth = widths[columnIndex]
				if (columnConfig is TableConfig.ColumnConfig.ComposableColumnConfig) {
					lastRange.forEachIndexed { index, item ->
						Box(modifier = Modifier.offset(x = column, y = index + 1)) {
							columnConfig.content(item)
						}
					}
				}
				column += columnWidth + 1
			}
		}

		Spacer(modifier = Modifier.fillMaxSize())
	}
}