package app.dependencies

import app.data.Project
import app.dependencies.docker.DockerContainer

/**
 * Base abstraction for infrastructure dependencies managed by Werkbank.
 * Implementations must be idempotent and safe to call multiple times.
 *
 * The lifecycle is split into explicit phases so that orchestration (ordering,
 * ref-counting, updates) can happen outside the individual dependency:
 *
 *   configure() -> provision() -> start() -> ensureReady()
 *
 * Each phase is optional to override; the defaults are no-ops where a dependency
 * has nothing to do.
 */
interface AppDependency {
    /** Unique identifier (e.g., "traefik", "unbound", "postgres18"). */
    val key: String

    /**
     * A list of reverse proxy records that should be added to the Traefik config.
     */
    val reverseProxyRecords: List<ReverseProxyRecord>

    /**
     * All domains that are exposed to anywhere outside just werkbank. All domains provided here will receive
     * an SSL certificate.
     */
    val webfacingDomains: List<String>

    /**
     * Other dependencies that must be brought up before this one. Because every
     * dependency is a singleton, the graph is expressed with the actual objects
     * so the orchestrator can topologically sort by identity.
     */
    val dependsOn: List<AppDependency> get() = emptyList()

    /**
     * Prepare files, configs, directories, hosts entries, etc.
     * Must be idempotent, cheap and must NOT require any container to be running.
     */
    suspend fun configure() {}

    /** Create the underlying container(s) and pull images if needed. Does NOT start anything. */
    suspend fun provision()

    /** Start the underlying service/container. */
    suspend fun start()

    /**
     * Runtime work that requires running containers (creating databases, realms,
     * vhosts, ...). Runs after [start]. No-op by default.
     */
    suspend fun ensureReady() {}

    /** Stop the underlying service/container if running. */
    suspend fun stop()

    /**
     * The containers owned by this dependency. Used by [update] to re-pull images
     * and recreate containers whose image changed.
     */
    suspend fun managedContainers(): List<DockerContainer> = emptyList()

    /**
     * Refreshes the dependency: regenerates config (new routes, certificates, ...),
     * re-pulls images and recreates any container whose spec or image changed, then
     * brings it back up.
     */
    suspend fun update() {
        configure()
        managedContainers().forEach { it.update() }
        provision()
        start()
        ensureReady()
    }

    /**
     * Determines if a given project requires this dependency.
     * Implementations should be fast and side‑effect free.
     */
    fun isRequiredFor(project: Project): Boolean

    /** If true, this dependency is required regardless of project configuration. */
    fun isAlwaysRequired(): Boolean = false
}

data class ReverseProxyRecord(
    val domain: String,
    val port: Int,
    val containerName: String
)
