package commands

import app.completion.setupCompletions
import app.dependencies.openssl.OpensslHandler
import app.werkbank.BuildKonfig
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import commands.completion.CompletionBaseCommand
import commands.config.ConfigCommand
import commands.dependencies.DependenciesCommand
import commands.down.DownCommand
import commands.exposed.ExposedCommand
import commands.login.LoginCommand
import commands.login.LogoutCommand
import commands.poweroff.PoweroffCommand
import commands.service.ServiceCommand
import commands.setup.SetupCommand
import commands.tunnel.TunnelCommand
import commands.up.UpCommand
import commands.update.UpdateCommand
import io.github.z4kn4fein.semver.Version
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.system.exitProcess
import kotlin.test.assertTrue

class MainCommand : SuspendingCliktCommand("wb"), KoinComponent {
    private val opensslHandler by inject<OpensslHandler>()

    val regenerateRootCa by option(
        "--regenerate-root-ca",
        help = "Regenerates the root CA certificate"
    )
        .flag()

    val showVersion by option(
        "--version", "-v",
        help = "Shows the version of the CLI"
    ).flag()

    override val invokeWithoutSubcommand: Boolean = true

    override suspend fun run() {
        // Generate/update shell completions and set up watcher before invoking CLI
        setupCompletions(this)

        if (showVersion) {
            println(BuildKonfig.version)
            exitProcess(0)
        }

        if (regenerateRootCa) {
            assertTrue(opensslHandler.isOpensslAvailable.await())
            opensslHandler.createRootCa()
        }
    }

    init {
        subcommands(
            CompletionBaseCommand(),
            SetupCommand(),
            UpCommand(),
            DownCommand(),
            PoweroffCommand(),
            ServiceCommand(),
            ConfigCommand(),
            ExposedCommand(),
            DependenciesCommand(),
            LoginCommand(),
            LogoutCommand(),
            TunnelCommand(),
            UpdateCommand(),
        )
    }
}