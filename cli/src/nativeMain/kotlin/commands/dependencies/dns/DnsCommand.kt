package commands.dependencies.dns

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import org.koin.core.component.KoinComponent

class DnsCommand: SuspendingCliktCommand("dns"), KoinComponent {
    override suspend fun run() {}

    init {
        this.subcommands(
            RebuildCommand(),
        )
    }
}