package commands.config

import app.config.MainConfig
import app.config.WerkbankConfig
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class StringCommandConfig(
    private val key: String,
    private val set: suspend (value: String) -> Unit,
    private val reset: (suspend () -> Unit)? = null,
    private val getValue: (WerkbankConfig) -> String,
) : SuspendingCliktCommand(key), KoinComponent {
    val mainConfig by inject<MainConfig>()
    override val invokeWithoutSubcommand: Boolean = true

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return
        val config = mainConfig.getConfig()
        val value = getValue(config)
        echo(buildStyledString {
            green { +key }
            +" is set to "
            blue { +value }
        })
    }

    init {
        this.subcommands(
            StringSetCommand(
                key = key,
                set = set,
            )
        ).let {
            if (reset != null) {
                it.subcommands(
                    StringResetCommand(
                        reset = reset,
                    )
                )
            } else it
        }
    }
}