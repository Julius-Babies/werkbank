package commands.tunnel.ui

import androidx.compose.runtime.Composable
import app.ui.Text
import app.ui.TextOverflow
import com.jakewharton.mosaic.layout.fillMaxSize
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.TextStyle
import commands.tunnel.ConnectionStatusLogEntry
import io.ktor.util.logging.LogLevel
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import util.Border
import util.BorderedTitledBox

/** How many of the newest entries the status log shows. */
private const val VISIBLE_ENTRIES = 3

/** The top and bottom line of the frame around the entries. */
private const val BORDER_ROWS = 2

private val timeFormat = LocalDateTime.Format {
    hour(Padding.ZERO)
    char(':')
    minute(Padding.ZERO)
    char(':')
    second(Padding.ZERO)
}

/**
 * The newest connection events, oldest first. Always [VISIBLE_ENTRIES] rows tall so the request
 * table above it does not jump around while the log fills up.
 */
@Composable
fun ConnectionStatusLog(
    entries: List<ConnectionStatusLogEntry>,
    modifier: Modifier = Modifier,
) {
    val visible = entries.takeLast(VISIBLE_ENTRIES)
    BorderedTitledBox(
        title = "Connection log",
        modifier = modifier.height(VISIBLE_ENTRIES + BORDER_ROWS),
        titleColor = Color(128, 128, 128),
        borderColor = Color(128, 128, 128),
        borderVariant = Border.Variant.Custom(
            topLeft = Border.Corner.Rounded,
            topRight = Border.Corner.Rounded,
            bottomLeft = Border.Corner.Rounded,
            bottomRight = Border.Corner.Rounded,
            titleLeft = Border.Corner.Hard,
            titleRight = Border.Corner.Hard,
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            repeat(VISIBLE_ENTRIES - visible.size) { Spacer(Modifier.height(1).fillMaxWidth()) }
            visible.forEach { entry ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        value = entry.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).format(timeFormat),
                        textStyle = TextStyle.Dim,
                    )
                    Text(" ")
                    Text(
                        value = entry.level.label,
                        color = entry.level.color,
                    )
                    Text(" ")
                    Box(Modifier.weight(1f)) {
                        Text(
                            // Only the headline goes on screen; the details are in the log file.
                            value = entry.message.lines().first(),
                            color = entry.level.color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Padded so the messages of consecutive entries line up. */
private val LogLevel.label: String get() = name.padEnd(5)

private val LogLevel.color: Color get() = when (this) {
    LogLevel.ERROR -> Color.Red
    LogLevel.WARN -> Color.Yellow
    LogLevel.INFO, LogLevel.DEBUG, LogLevel.TRACE -> Color.Unspecified
}
