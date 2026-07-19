package commands.dependencies

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import commands.dependencies.dns.DnsCommand
import commands.dependencies.reverse_proxy.ReverseProxyCommand
import org.koin.core.component.KoinComponent

class DependenciesCommand: SuspendingCliktCommand("dependencies"), KoinComponent {

    override suspend fun run() {
    }

    init {
        this.subcommands(
            DnsCommand(),
            ReverseProxyCommand(),
            UpdateCommand(),
        )
    }
}