package app.dependencies

import app.data.Project
import app.data.extensions.project.hasRunningContainers
import app.repository.ProjectRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.buildStyledString

/**
 * Central place that drives the [AppDependency] lifecycle. Ordering is derived
 * from the [AppDependency.dependsOn] graph via a topological sort, so individual
 * dependencies no longer need to know about each other.
 */
class DependencyOrchestrator : KoinComponent {
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))
    private val projectRepository by inject<ProjectRepository>()

    /**
     * Brings up every dependency required for [project], or all of them when
     * [project] is `null` (full infrastructure). Dependencies pulled in via
     * `dependsOn` are included even if no project requires them directly.
     */
    suspend fun up(project: Project?) {
        val targets = if (project == null) dependencies.toSet()
        else dependencies.filter { it.isAlwaysRequired() || it.isRequiredFor(project) }.toSet()
        bringUp(targets)
    }

    /** Brings up a single dependency together with its `dependsOn` closure. */
    suspend fun up(dependency: AppDependency) = bringUp(setOf(dependency))

    private suspend fun bringUp(targets: Set<AppDependency>) {
        orderedForStartup(targets).forEach { dep -> bringUpOne(dep) }
    }

    private suspend fun bringUpOne(dep: AppDependency) {
        dep.configure()
        dep.provision()
        dep.start()
        dep.ensureReady()
    }

    /**
     * Stops the given [project] and every dependency it uses that is no longer
     * needed by any other project with running containers (generic ref-counting).
     */
    suspend fun down(project: Project) = coroutineScope {
        launch {
            println(buildStyledString { cyan { +"Stopping project ${project.name}..." } })
            project.stop()
        }

        val otherProjects = projectRepository.getAllProjects().filter { it.id != project.id }
        dependencies.forEach { dep ->
            if (!dep.isRequiredFor(project)) return@forEach
            launch {
                val stillNeeded = otherProjects.any { other ->
                    dep.isRequiredFor(other) && other.hasRunningContainers()
                }
                if (!stillNeeded) dep.stop()
            }
        }
    }

    /** Stops all dependencies and all projects. */
    suspend fun poweroff() = coroutineScope {
        dependencies.forEach { dep ->
            launch {
                println(buildStyledString { blue { +"Stopping dependency '${dep.key}'" } })
                dep.stop()
            }
        }
        projectRepository.getAllProjects().forEach { project ->
            launch { project.stop() }
        }
    }

    /**
     * Updates the given dependencies (by key), or all of them when [keys] is
     * empty. Dependencies pulled in only via `dependsOn` are brought up (so a
     * target has its prerequisites running) but not themselves updated.
     */
    suspend fun update(keys: List<String> = emptyList()) {
        val targets = if (keys.isEmpty()) dependencies.toSet()
        else dependencies.filter { it.key in keys }.toSet()
        orderedForStartup(targets).forEach { dep ->
            if (dep in targets) dep.update() else bringUpOne(dep)
        }
    }

    /**
     * Topologically orders [targets] plus their transitive `dependsOn` closure so
     * that every dependency appears before the dependents that require it.
     */
    private fun orderedForStartup(targets: Set<AppDependency>): List<AppDependency> {
        val ordered = mutableListOf<AppDependency>()
        val visited = mutableSetOf<AppDependency>()
        val onStack = mutableSetOf<AppDependency>()

        fun visit(dep: AppDependency) {
            if (dep in visited) return
            require(dep !in onStack) { "Cyclic dependency detected involving '${dep.key}'" }
            onStack += dep
            dep.dependsOn.forEach { visit(it) }
            onStack -= dep
            visited += dep
            ordered += dep
        }

        targets.forEach { visit(it) }
        return ordered
    }
}
