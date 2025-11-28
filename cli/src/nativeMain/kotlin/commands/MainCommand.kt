package commands

import app.dependencies.openssl.OpensslHandler
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import commands.setup.SetupCommand
import commands.up.UpCommand
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.test.assertTrue

class MainCommand : SuspendingCliktCommand("werkbank"), KoinComponent {
    private val opensslHandler by inject<OpensslHandler>()

    val regenerateRootCa by option(
        "--regenerate-root-ca",
        help = "Regenerates the root CA certificate"
    )
        .flag()

    override val invokeWithoutSubcommand: Boolean = true

    override suspend fun run() {
        if (regenerateRootCa) {
            assertTrue(opensslHandler.isOpensslAvailable.await())
            opensslHandler.createRootCa()
        }
    }

    init {
        subcommands(SetupCommand(), UpCommand())
    }
}