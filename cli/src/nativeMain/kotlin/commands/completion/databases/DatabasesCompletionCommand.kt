package commands.completion.databases

import com.github.ajalt.clikt.core.subcommands
import commands.completion.CompletionGroupCommand

/** Groups the database name completions by database system. */
class DatabasesCompletionCommand : CompletionGroupCommand("databases") {

    init {
        this.subcommands(
            Postgres18DatabasesCompletionCommand(),
        )
    }
}
