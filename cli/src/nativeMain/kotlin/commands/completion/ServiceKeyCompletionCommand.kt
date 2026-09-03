package commands.completion

import app.dependencies.AppDependency
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/** Keys of the werkbank services that are reachable through the reverse proxy. */
class ServiceKeyCompletionCommand : CompletionCommand("service-key") {

    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))

    override suspend fun candidates(): Collection<String> = dependencies
        .filter { it.webfacingDomains.isNotEmpty() }
        .map { it.key }
}
