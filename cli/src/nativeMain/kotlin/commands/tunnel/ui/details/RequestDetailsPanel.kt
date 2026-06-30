package commands.tunnel.ui.details

import androidx.compose.runtime.Composable
import app.ui.Text
import app.ui.TextOverflow
import com.jakewharton.mosaic.layout.fillMaxSize
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Arrangement
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.TextStyle
import commands.tunnel.Request
import commands.tunnel.ui.httpMethodColors
import util.BorderedTitledBox
import util.RIGHT_ARROW

@Composable
fun RequestDetailsPanel(
    request: Request,
) {
    BorderedTitledBox(
        modifier = Modifier.fillMaxSize(),
        title = "Request details " + request.requestId,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row {
                val (foreground, background) = request.method.httpMethodColors()
                Text(
                    value = request.method.uppercase(),
                    color = foreground,
                    background = background,
                )
                Text(" ")
                Box(Modifier.weight(1f)) {
                    Text(value = request.path, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(1))

            Row(
                modifier = Modifier.weight(1f, true),
                horizontalArrangement = Arrangement.spacedBy(1),
            ) {
                Column(Modifier.weight(1f, true)) {
                    Text(
                        value = "Request Headers",
                        color = Color.Cyan,
                        textStyle = TextStyle.Bold
                    )
                    request.headers.sortedBy { it.first }.forEach { (key, value) ->
                        Row {
                            Text(
                                value = "$key: ",
                                textStyle = TextStyle.Bold,
                            )
                            Text(
                                value = value,
                                textStyle = TextStyle.Dim,
                            )
                        }
                    }
                }

                if (request.result is Request.Result.Success) Column(Modifier.weight(1f, true)) {
                    Text(
                        value = "Response Headers",
                        color = Color.Cyan,
                        textStyle = TextStyle.Bold
                    )
                    request.result.headers.sortedBy { it.first }.forEach { (key, value) ->
                        Row {
                            Text(
                                value = "$key: ",
                                textStyle = TextStyle.Bold,
                            )
                            Text(
                                value = value,
                                textStyle = TextStyle.Dim,
                            )
                        }
                    }
                }
            }
        }
    }
}