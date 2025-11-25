package commands

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import commands.setup.SetupCommand

class MainCommand : SuspendingCliktCommand("werkbank") {
    override suspend fun run() {}

    init {
        subcommands(SetupCommand())
    }
}