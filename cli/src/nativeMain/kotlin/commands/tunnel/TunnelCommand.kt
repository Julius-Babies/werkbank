package commands.tunnel

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.process.currentProcessId
import app.storage.TunnelPidFile
import app.storage.isDevMode
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaic
import app.ui.*
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import commands.tunnel.ui.ConnectionStatusLog
import commands.tunnel.ui.RequestTable
import commands.tunnel.ui.details.RequestDetailsPanel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TunnelCommand : SuspendingCliktCommand("tunnel"), KoinComponent {

    override suspend fun run() {
        val viewModel = TunnelViewModel()

        print("\u001b[?1049h")

        try {
            coroutineScope {
                // Nothing inside the composition can stop it, so the UI runs as its own job that the
                // dialog's exit button cancels from out here.
                val ui = launch {
                    runMosaic(NonInteractivePolicy.Ignore) { TunnelScreen(viewModel) }
                }
                val exitWatcher = launch {
                    viewModel.awaitExit()
                    ui.cancel()
                }
                ui.join()
                exitWatcher.cancel()
            }
        } finally {
            // The connect loop clears this itself, but not when it is cancelled from under us.
            TunnelPidFile.clear(currentProcessId())
            print("\u001b[?1049l")
        }

        when (viewModel.state.value.connectionState) {
            is TunnelState.ConnectionState.Connected -> println("Tunnel closed")
            is TunnelState.ConnectionState.Connecting -> println("Tunnel interrupted")
            is TunnelState.ConnectionState.Retrying -> println("Tunnel connection failed")
            is TunnelState.ConnectionState.Rejected -> println("Another tunnel is already running for this account")
        }
    }
}

@Composable
private fun TunnelScreen(viewModel: TunnelViewModel) {
    val terminal = LocalTerminalState.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val connectionStatusLog by viewModel.connectionStatusLog.collectAsStateWithLifecycle()
    val rejected = state.connectionState as? TunnelState.ConnectionState.Rejected

    Box(
        modifier = Modifier
            .width(terminal.size.columns)
            .height(terminal.size.rows)
    ) {
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
                            if (state.showRequestDetailsPanel || rejected != null) return@onKeyEvent false
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
                // The list is capped, so the highlighted request may have been evicted.
                val highlightedRequest = requests.firstOrNull { it.requestId == state.highlightedRequestId }
                if (state.showRequestDetailsPanel && highlightedRequest != null) {
                    Column(
                        modifier = Modifier
                            .weight(1f, true)
                            .onKeyEvent { event ->
                                if (rejected != null) return@onKeyEvent false
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
                            request = highlightedRequest,
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
                        is TunnelState.ConnectionState.Rejected -> {
                            Text(
                                value = "Tunnel already running",
                                color = Color.Red,
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

            ConnectionStatusLog(
                entries = connectionStatusLog,
                modifier = Modifier.width(terminal.size.columns),
            )
        }

        if (rejected != null) {
            val localTunnel = rejected.localTunnel
            Dialog(
                title = "Tunnel already running",
                description = buildString {
                    append(rejected.reason)
                    append(".\n\n")
                    if (localTunnel == null) {
                        append("Only one tunnel can be connected per account at a time. Stop the other one ")
                        append("and retry, or exit.")
                    } else {
                        append("It is running on this machine:\n\n")
                        append("PID ${localTunnel.pid}\n")
                        append("${localTunnel.command}\n")
                        localTunnel.uptime?.let { append("running for ${it.inWholeSeconds.seconds}\n") }
                        append("\nStopping it frees the slot for this tunnel.")
                    }
                },
                buttons = listOf(
                    if (localTunnel == null) {
                        DialogButton("Retry") { viewModel.onRetryRejectedTunnel() }
                    } else {
                        DialogButton("Stop it and retry") { viewModel.onStopLocalTunnelAndRetry() }
                    },
                    DialogButton("Exit") { viewModel.onExit() },
                ),
                onDismiss = { viewModel.onExit() },
            )
        }
    }
}
