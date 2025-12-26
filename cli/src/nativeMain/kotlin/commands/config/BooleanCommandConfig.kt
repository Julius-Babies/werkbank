package commands.config

import app.config.MainConfig
import app.config.WerkbankConfig
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class BooleanCommandConfig(
    private val key: String,
    private val getValue: (WerkbankConfig) -> Boolean,
) : SuspendingCliktCommand(key), KoinComponent {
    val mainConfig by inject<MainConfig>()

    override val invokeWithoutSubcommand: Boolean = true

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return
        val config = mainConfig.getConfig()
        val value = getValue(config)
        println(buildStyledString {
            green { +key }
            +" is set to "
            blue { +value.toString() }
        })
    }

    init {
        this.subcommands(
            BooleanSetCommand(
                key = key,
                set = { value ->
                    mainConfig.updateConfig {
                        it.copy(
                            androidDns = it.androidDns.copy(
                                enabled = value
                            )
                        )
                    }
                }
            )
        )
    }
}