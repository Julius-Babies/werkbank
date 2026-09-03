package commands.postgres18

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import commands.postgres18.query.QueryCommand

class Postgres18Command : SuspendingCliktCommand("postgres18") {

    override val invokeWithoutSubcommand: Boolean = false

    override suspend fun run() {

    }

    init {
        this.subcommands(
            QueryCommand(),
        )
    }
}