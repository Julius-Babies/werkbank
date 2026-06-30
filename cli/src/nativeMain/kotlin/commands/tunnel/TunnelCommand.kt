package commands.tunnel

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.storage.isDevMode
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicBlocking
import app.ui.*
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import commands.tunnel.ui.RequestTable
import commands.tunnel.ui.details.RequestDetailsPanel
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
						.height(terminal.size.rows)
						.onKeyEvent { event ->
                            if (event.ctrl && event.key == "c") {
                                viewModel.onCancel()
                                false
                            } else {
                                false
                            }
                        }
                ) {
                    Column(Modifier.weight(1f, true).fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .weight(1f, true)
                                .padding(top = 2)
                                .onKeyEvent { event ->
                                    if (state.showRequestDetailsPanel) return@onKeyEvent false
                                    when (event.key) {
                                        "ArrowDown" -> {
                                            viewModel.onSelectPrevious()
                                            return@onKeyEvent true
                                        }
                                        "ArrowUp" -> {
                                            viewModel.onSelectNext()
                                            return@onKeyEvent true
                                        }
                                        "Home" -> {
                                            viewModel.onSelectLatest()
                                            return@onKeyEvent true
                                        }
                                        "End" -> {
                                            viewModel.onSelectOldest()
                                            return@onKeyEvent true
                                        }
                                        "Enter" -> {
                                            if (state.highlightedRequestId != null) viewModel.onShowRequestDetails()
                                            return@onKeyEvent true
                                        }
                                    }

                                    return@onKeyEvent false
                                }
                        ) {
                            RequestTable(
                                state = state,
                                requests = requests,
                            )
                        }
                        if (state.showRequestDetailsPanel) {
                            Column(
                                modifier = Modifier
                                    .weight(1f, true)
                                    .onKeyEvent { event ->
                                        when (event.key) {
                                            "Escape" -> {
                                                viewModel.onHideRequestDetails()
                                                return@onKeyEvent true
                                            }
                                            "ArrowDown" -> {
                                                viewModel.onSelectPrevious()
                                                return@onKeyEvent true
                                            }
                                            "ArrowUp" -> {
                                                viewModel.onSelectNext()
                                                return@onKeyEvent true
                                            }
                                        }

                                        return@onKeyEvent false
                                    }
                            ) {
                                RequestDetailsPanel(
                                    request = requests.first { it.requestId == state.highlightedRequestId },
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(terminal.size.columns)
                            .height(1)
                    ) {
                        Row {
                            when (val connectionState = state.connectionState) {
                                is TunnelState.ConnectionState.Connected -> {
                                    AnimatableCharacter(
                                        characters = listOf("•", "●"),
                                        color = Color.Green,
                                    )
                                    Text(
                                        " Connected ",
                                        color = Color.Green,
                                    )
                                    if (connectionState.currentPing != null) {
                                        Text("(${connectionState.currentPing.inWholeMilliseconds}ms)", color = Color.Unspecified)
                                    }
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
                                    Text(" ")
                                    Text(
                                        value = connectionState.throwable.message ?: "Unknown error",
                                        color = Color.Red,
                                    )
                                }
                            }
                            if (isDevMode) Text(" (Dev) ", color = Color.Yellow)
                            if (state.highlightedRequestId != null) Text(" " + state.highlightedRequestId, color = Color.Blue)
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
