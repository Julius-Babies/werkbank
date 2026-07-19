package app.dependencies

import app.data.Project

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
     * Keys of other dependencies that must be brought up before this one.
     * Makes ordering explicit instead of relying on the registration order.
     */
    val dependsOn: List<String> get() = emptyList()

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
     * Re-pull the image / rebuild the container when its tag or digest changed.
     * Refined in Phase 3; the default simply re-provisions.
     */
    suspend fun update() {
        provision()
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
