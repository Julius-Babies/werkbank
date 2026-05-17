package commands.dependencies.dns

import app.dependencies.android_dns.Unbound
import app.dependencies.docker.DockerContainer
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RebuildCommand: SuspendingCliktCommand("rebuild"), KoinComponent {

    private val unbound by inject<Unbound>()

    private val recreate by option("--recreate", help = "Recreates the container").flag()

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return

        if (recreate) {
            if (unbound.getContainer().getState() == DockerContainer.State.Running) {
                println("Stopping Unbound...")
                unbound.stop()
            }

            if (unbound.getContainer().getState() == DockerContainer.State.Stopped) {
                println("Deleting Unbound container...")
                unbound.getContainer().delete()
            }

            println("Recreating Unbound container...")
            unbound.unboundStorageRoot.listFiles().forEach { it.delete(true) }
            unbound.initialize()
            unbound.start()
        } else {
            if (unbound.getContainer().getState() == DockerContainer.State.Running) {
                println("Restarting Unbound...")
                unbound.stop()
                unbound.writeConfigFile()
                println("Unbound DNS config rebuilt.")
                unbound.start()
                println("Unbound restarted.")
            }
        }

    }
}