package commands.dependencies.reverse_proxy

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands

class ReverseProxyCommand: SuspendingCliktCommand("reverse-proxy") {
    override suspend fun run() {
    }

    init {
        this.subcommands(
            RebuildCommand()
        )
    }
}