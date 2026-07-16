package app.werkbank.util

import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Launches a long-lived per-connection coroutine whose failure must never escape to its parent.
 *
 * WebSocket relay/ping loops call `sendSerialized(...)` in loops; when the peer disconnects those
 * throw. Because the loops are plain `launch { }` children of the session (and ultimately the
 * Application) scope, an uncaught exception here propagates up the Job hierarchy, cancels the
 * Application, and the Koin Ktor plugin then closes the `_root_` scope on shutdown. From that point
 * every request that resolves a dependency via `inject` fails with `ClosedScopeException` and the
 * whole API is dead. Containing the failure here keeps it scoped to the one connection.
 *
 * A normal disconnect just ends the job quietly; unexpected errors are logged. [CancellationException]
 * is rethrown so structured cancellation (e.g. the session closing) still works.
 */
fun CoroutineScope.launchConnectionJob(
    application: Application,
    name: String,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val log = application.environment.log
        if (e.isConnectionClosed()) {
            // Routine: the peer went away mid-loop. Nothing to do, keep it quiet.
            log.debug("Connection job '{}' ended: {}", name, e.message)
        } else {
            // A real bug — surface it with a stack trace so the actual cause is visible instead of
            // only the downstream ClosedScopeException a cancelled Application would produce.
            log.warn("Connection job '$name' failed unexpectedly", e)
        }
    }
}

/** True for the ordinary "the other side disconnected" exceptions raised by websocket/channel I/O. */
private fun Throwable.isConnectionClosed(): Boolean =
    this is java.io.IOException || this::class.simpleName?.contains("Closed", ignoreCase = true) == true

