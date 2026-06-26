package commands.tunnel

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.storage.isDevMode
import app.ui.*
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicBlocking
import com.jakewharton.mosaic.ui.*
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class TunnelCommand : SuspendingCliktCommand("tunnel"), KoinComponent {

    override suspend fun run() {
        val viewModel = TunnelViewModel()

        print("\u001b[?1049h")

        try {
            runMosaicBlocking(NonInteractivePolicy.Ignore) {
                val terminal = LocalTerminalState.current
                val state by viewModel.state.collectAsStateWithLifecycle()
                val requests by viewModel.requests.collectAsStateWithLifecycle()

                Column(
                    modifier = Modifier
                        .width(terminal.size.columns)
                        .requiredHeight(terminal.size.rows - 1)
                        .onKeyEvent { event ->
                            if (event.ctrl && event.key == "c") {
                                viewModel.onCancel()
                                false
                            } else {
                                false
                            }
                        }
                ) {
                    Column(modifier = Modifier.fillMaxHeight().padding(top = 1)) {
                        Table(
                            TableData(requests),
                            TableConfig(
                                titleColor = Color.White,
                                columnConfigs = listOf(
                                    TableConfig.ColumnConfig.ComposableColumnConfig(
                                        title = "METHOD",
                                        content = { request ->
                                            val (foreground, background) = when (request.method.uppercase()) {
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

                                            Text(
                                                value = request.method.uppercase(),
                                                color = foreground,
                                                background = background,
                                            )
                                        },
                                        width = ColumnWidth.Fixed(10)
                                    ),
                                    TableConfig.ColumnConfig.ComposableColumnConfig(
                                        title = "TARGET",
                                        content = { request ->
                                            Row {
                                                Text(
                                                    value = buildString {
                                                        append(request.project)
                                                        append(".")
                                                    }
                                                )
                                                Text(
                                                    value = request.service,
                                                    textStyle = TextStyle.Italic
                                                )
                                                Text(
                                                    value = "/${request.path.removePrefix("/")}"
                                                )
                                            }
                                        },
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
                                                    Text(request.result.statusCode.toString(), color = Color.Green)
                                                    Text(" (${request.result.finishedAt.toEpochMilliseconds() - request.startedAt.toEpochMilliseconds()}ms)", color = Color.White)
                                                }
                                            }
                                        },
                                        width = ColumnWidth.Weight(1)
                                    )
                                )
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(terminal.size.columns)
                    ) {
                        Row {
                            when (val connectionState = state.connectionState) {
                                is TunnelState.ConnectionState.Connected -> {
                                    AnimatableCharacter(
                                        characters = listOf("•", "●"),
                                        color = Color.Green,
                                    )
                                    Text(
                                        " Connected",
                                        color = Color.Green,
                                    )
                                }
                                is TunnelState.ConnectionState.Connecting -> {
                                    AnimatableCharacter(
                                        characters = AnimatableCharacters.DotSpinner,
                                        delay = 200.milliseconds,
                                        color = Color.Blue,
                                    )
                                    Text(
                                        " Connecting..."
                                    )
                                }
                                is TunnelState.ConnectionState.Retrying -> {
                                    var remainingSeconds by remember { mutableStateOf(connectionState.waitUntil.epochSeconds - Clock.System.now().epochSeconds) }
                                    LaunchedEffect(Unit) {
                                        while (remainingSeconds > 0) {
                                            remainingSeconds = connectionState.waitUntil.epochSeconds - Clock.System.now().epochSeconds
                                            delay(50.milliseconds)
                                        }
                                    }
                                    Text(
                                        value = buildString {
                                            append("Retrying in ")
                                            append(remainingSeconds)
                                            append(" second")
                                            if (remainingSeconds != 1L) append("s")
                                            append("...")
                                        }
                                    )
                                }
                            }
                            if (isDevMode) Text(" (Dev) ", color = Color.Yellow)
                            Spacer(Modifier.weight(1f, true))
                            Text(
                                value = "CTRL+C to exit",
                                color = Color.Blue,
                            )
                        }
                    }
                }
            }
        } finally {
            print("\u001b[?1049l")
        }

        when (val s = viewModel.state.value.connectionState) {
            is TunnelState.ConnectionState.Connected -> println("Tunnel closed")
            is TunnelState.ConnectionState.Connecting -> println("Tunnel interrupted")
            is TunnelState.ConnectionState.Retrying -> println("Tunnel connection failed")
        }
    }
}
