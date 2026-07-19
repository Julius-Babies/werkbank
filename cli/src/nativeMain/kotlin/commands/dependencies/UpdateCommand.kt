package commands.dependencies

import app.dependencies.AppDependency
import app.dependencies.DependencyOrchestrator
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.buildStyledString

class UpdateCommand : SuspendingCliktCommand("update"), KoinComponent {
    private val orchestrator by inject<DependencyOrchestrator>()
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))

    private val keys by argument(
        name = "dependency",
        help = "Dependencies to update (by key). Updates all when omitted."
    ).multiple()

    override suspend fun run() {
        val known = dependencies.map { it.key }.toSet()
        val unknown = keys.filterNot { it in known }
        if (unknown.isNotEmpty()) {
            println(buildStyledString {
                red { +"Unknown dependency: ${unknown.joinToString(", ")}" }
            })
            println(buildStyledString { gray { +"Available: ${known.sorted().joinToString(", ")}" } })
            return
        }
        orchestrator.update(keys)
    }
}
