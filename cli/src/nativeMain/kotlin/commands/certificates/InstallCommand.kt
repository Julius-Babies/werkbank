package commands.certificates

import app.dependencies.openssl.OpensslHandler
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.system.exitProcess

class InstallCommand : SuspendingCliktCommand("install"), KoinComponent {
    private val opensslHandler by inject<OpensslHandler>()

    override fun help(context: Context) = "Installs the root CA in the system trust store"

    override suspend fun run() {
        if (!opensslHandler.rootCaFile.exists()) {
            println(buildStyledString { red { +"There is no root CA yet" } })
            exitProcess(1)
        }

        opensslHandler.installRootCaInSystem()
    }
}
