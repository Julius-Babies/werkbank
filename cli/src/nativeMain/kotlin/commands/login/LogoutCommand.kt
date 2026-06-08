package commands.login

import app.config.MainConfig
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class LogoutCommand: SuspendingCliktCommand("logout"), KoinComponent {
    private val mainConfig by inject<MainConfig>()

    override suspend fun run() {
        mainConfig.updateConfig {
            it.copy(auth = null)
        }

        println(buildStyledString { green { +"Logged out" } })
    }
}