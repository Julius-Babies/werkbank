package commands.tunnel.ui

import androidx.compose.runtime.Composable
import app.ui.*
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import commands.tunnel.Request
import commands.tunnel.TunnelState
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import util.RIGHT_ARROW
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun RequestTable(
    requests: List<Request>,
    state: TunnelState,
) {
    Table(
        TableData(requests),
        TableConfig(
            titleColor = Color.White,
            columnConfigs = listOf(
                TableConfig.ColumnConfig.StringColumnConfig(
                    title = "",
                    stringFromItem = { request -> if (state.highlightedRequestId == request.requestId) " $RIGHT_ARROW" else " " },
                    valueColor = Color.Unspecified,
                    width = ColumnWidth.Fixed(3),
                ),
                TableConfig.ColumnConfig.StringColumnConfig(
                    title = "TIME",
                    stringFromItem = { request ->
                        request.startedAt.toLocalDateTime(TimeZone.currentSystemDefault()).format(LocalDateTime.Format {
                            year(Padding.ZERO)
                            char('-')
                            monthNumber(Padding.ZERO)
                            char('-')
                            day(Padding.ZERO)
                            char(' ')
                            hour(Padding.ZERO)
                            char(':')
                            minute(Padding.ZERO)
                            char(':')
                            second(Padding.ZERO)
                        })
                    },
                    valueColor = Color.Unspecified,
                    width = ColumnWidth.Fixed(20)
                ),
                TableConfig.ColumnConfig.ComposableColumnConfig(
                    title = "METHOD",
                    content = { request ->
                        val (foreground, background) = request.method.httpMethodColors()

                        Text(
                            value = request.method.uppercase(),
                            color = foreground,
                            background = background,
                        )
                    },
                    width = ColumnWidth.Fixed(10)
                ),
                TableConfig.ColumnConfig.StringColumnConfig(
                    title = "TARGET",
                    stringFromItem = { request ->
                        buildString {
                            append(request.project)
                            append(".")
                            append(request.service)
                            append("/")
                            append(request.path.removePrefix("/"))
                        }
                    },
                    valueColor = Color.Unspecified,
                    width = ColumnWidth.Weight(6)
                ),
                TableConfig.ColumnConfig.StringColumnConfig(
                    title = "DESTINATION",
                    stringFromItem = { request -> request.targetUrl },
                    valueColor = Color.Unspecified,
                    width = ColumnWidth.Weight(6)
                ),
                TableConfig.ColumnConfig.ComposableColumnConfig(
                    title = "STATUS",
                    content = { request ->
                        when (request.result) {
                            null -> AnimatableCharacter(
                                characters = AnimatableCharacters.DotSpinner,
                                delay = 200.milliseconds,
                            )
                            is Request.Result.Timeout -> Text("Timeout", color = Color.Red)
                            is Request.Result.ServiceNotRunning -> Text("Down", color = Color.Red)
                            is Request.Result.Success -> Row {
                                val ms = request.result.finishedAt.toEpochMilliseconds() - request.startedAt.toEpochMilliseconds()
                                val timeText = if (ms >= 100_000L) {
                                    val secs = ms / 1000
                                    val dec = (ms % 1000) / 100
                                    "${secs}.${dec}s"
                                } else {
                                    "${ms}ms"
                                }
                                Text(request.result.statusCode.toString(), color = Color.Green)
                                Text(" ($timeText)", color = Color.White)
                            }
                        }
                    },
                    width = ColumnWidth.Fixed(16)
                )
            )
        )
    )
}

fun String.httpMethodColors(): Pair<Color, Color> {
    return when(uppercase()) {
        "GET" -> Color.Green to Color.Unspecified
        "POST" -> Color.Yellow to Color.Unspecified
        "PUT" -> Color.Blue to Color.Unspecified
        "PATCH" -> Color.Magenta to Color.Unspecified
        "DELETE" -> Color.Red to Color.Unspecified
        "HEAD" -> Color.Cyan to Color.Unspecified
        "OPTIONS" -> Color.Cyan to Color.Unspecified
        "WEBSOCKET" -> Color.Yellow to Color.Unspecified
        else -> Color.Black to Color.Unspecified
    }
}