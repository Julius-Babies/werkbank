package commands.config

import com.github.ajalt.clikt.command.SuspendingCliktCommand

class StringResetCommand(
    private val reset: suspend () -> Unit,
): SuspendingCliktCommand("reset") {
    override suspend fun run() {
        reset()
    }
}