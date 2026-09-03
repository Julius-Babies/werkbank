package commands.completion

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import org.koin.core.component.KoinComponent

/**
 * A command the shell calls to get the candidates for a single argument or option.
 * Candidates are printed space separated, which is the format
 * [com.github.ajalt.clikt.completion.CompletionCandidates.Custom.fromStdout] expects.
 */
abstract class CompletionCommand(name: String) : SuspendingCliktCommand(name), KoinComponent {

    override val hiddenFromHelp: Boolean = true

    abstract suspend fun candidates(): Collection<String>

    override suspend fun run() {
        println(candidates().distinct().sorted().joinToString(" "))
    }
}

/** Groups [CompletionCommand]s that belong to the same topic and does nothing on its own. */
abstract class CompletionGroupCommand(name: String) : SuspendingCliktCommand(name) {

    override val hiddenFromHelp: Boolean = true

    override suspend fun run() = Unit
}
