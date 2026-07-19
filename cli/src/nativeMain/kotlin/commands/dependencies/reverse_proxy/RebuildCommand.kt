package commands.dependencies.reverse_proxy

import app.dependencies.docker.DockerContainer
import app.dependencies.reverse_proxy.ReverseProxy
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RebuildCommand: SuspendingCliktCommand("rebuild"), KoinComponent {

    private val recreate by option("--recreate", help = "Recreates the reverse proxy container").flag()
    private val reverseProxy by inject<ReverseProxy>()

    override suspend fun run() {
        if (currentContext.invokedSubcommand != null) return

        val containers = reverseProxy.managedContainers()

        if (recreate) {
            if (containers.any { it.getState() == DockerContainer.State.Running }) {
                println("Stopping reverse proxy...")
                reverseProxy.stop()
            }

            containers
                .filter { it.getState() == DockerContainer.State.Stopped }
                .forEach {
                    println("Deleting reverse proxy container...")
                    it.delete()
                }

            println("Recreating reverse proxy container...")
        } else {
            if (containers.any { it.getState() == DockerContainer.State.Running }) {
                println("Restarting reverse proxy...")
                reverseProxy.stop()
            }
        }

        reverseProxy.configure()
        reverseProxy.provision()
        reverseProxy.start()
    }
}
