package commands.config

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands

class ConfigCommand : SuspendingCliktCommand("config") {
    override suspend fun run() {

    }

    init {
        this.subcommands(
            BooleanCommandConfig(
                key = "android-dns.enabled",
                getValue = { it.androidDns.enabled }
            )
        )
    }
}