package commands.config

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.parameters.arguments.argument
import util.buildStyledString

class BooleanSetCommand(
    private val key: String,
    private val set: (value: Boolean) -> Unit,
): SuspendingCliktCommand("set") {
    val value by argument(
        name = "value",
        help = "The boolean value to set (true/false)",
        completionCandidates = CompletionCandidates.Fixed("true", "false")
    )

    override suspend fun run() {
        val booleanValue = when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> {
                println(buildStyledString {
                    red {
                        +"The value must be either true or false - got $value"
                    }
                })
                return
            }
        }
        set(booleanValue)
        println(buildStyledString {
            green { +key }
            +" is set to "
            blue { +value }
        })
    }
}