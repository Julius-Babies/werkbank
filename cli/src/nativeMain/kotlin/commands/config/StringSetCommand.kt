package commands.config

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import util.buildStyledString

class StringSetCommand(
    private val key: String,
    private val set: suspend (value: String) -> Unit,
) : SuspendingCliktCommand("set") {
    val value by argument(
        name = "value",
        help = "The string value to set",
    )

    override suspend fun run() {
        set(value)
        echo(buildStyledString {
            green { +key }
            +" is set to "
            blue { +value }
        })
    }
}