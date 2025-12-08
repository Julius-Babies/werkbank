package app.dependencies

import app.data.Project

/**
 * Base abstraction for infrastructure dependencies managed by Werkbank.
 * Implementations must be idempotent and safe to call multiple times.
 */
interface AppDependency {
    /** Unique identifier (e.g., "traefik", "unbound", "postgres18"). */
    val key: String

    /** Prepare files, configs, volumes, networks, etc. Should not require the container to be running. */
    suspend fun initialize()

    /** Start the underlying service/container if required. */
    suspend fun start()

    /** Stop the underlying service/container if running. */
    suspend fun stop()

    /**
     * Determines if a given project requires this dependency.
     * Implementations should be fast and side‑effect free.
     */
    fun isRequiredFor(project: Project): Boolean

    /** If true, this dependency is required regardless of project configuration. */
    fun isAlwaysRequired(): Boolean = false
}
