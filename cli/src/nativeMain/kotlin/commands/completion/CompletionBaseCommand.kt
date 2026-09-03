package commands.completion

import com.github.ajalt.clikt.core.subcommands
import commands.completion.databases.DatabasesCompletionCommand

class CompletionBaseCommand : CompletionGroupCommand("completion") {

    init {
        this.subcommands(
            ServiceCompletionCommand(),
            ServiceKeyCompletionCommand(),
            ProjectCompletionCommand(),
            DatabasesCompletionCommand(),
        )
    }
}
