package commands.poweroff

import app.dependencies.DependencyOrchestrator
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PoweroffCommand: SuspendingCliktCommand("poweroff"), KoinComponent {
    private val orchestrator by inject<DependencyOrchestrator>()

    override suspend fun run() {
        orchestrator.poweroff()
    }
}
