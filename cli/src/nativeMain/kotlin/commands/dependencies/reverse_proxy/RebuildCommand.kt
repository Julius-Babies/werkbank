package commands.dependencies.reverse_proxy

import app.dependencies.docker.DockerContainer
import app.dependencies.reverse_proxy.TraefikManager
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RebuildCommand: SuspendingCliktCommand("rebuild"), KoinComponent {

    private val recreate by option("--recreate", help = "Recreates the reverse proxy container").flag()
    private val traefikManager by inject<TraefikManager>()

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return

        if (recreate) {
            if (traefikManager.getContainer().getState() == DockerContainer.State.Running) {
                println("Stopping Traefik...")
                traefikManager.stop()
            }

            if (traefikManager.getContainer().getState() == DockerContainer.State.Stopped) {
                println("Deleting Traefik container...")
                traefikManager.getContainer().delete()
            }

            println("Recreating Traefik container...")
            traefikManager.initialize()
            traefikManager.start()
        } else {
            if (traefikManager.getContainer().getState() == DockerContainer.State.Running) {
                println("Restarting Traefik...")
                traefikManager.stop()
            }
            traefikManager.initialize()
            traefikManager.start()
        }
    }
}