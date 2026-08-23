package app.werkbank.plugins.proxy

import app.werkbank.database.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.respond
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Outcome of resolving a proxied request's `<destination>.<username>` subdomain to its user, project
 * and (optionally) service. Resolved in a single database transaction because this runs on the hot
 * path of every proxied request.
 */
sealed class ProxyResolution {
    data class Success(
        val user: User,
        val project: Project,
        val service: Service?,
        /** The project owner's id, preloaded so the auth check needs no extra database round trip. */
        val ownerId: Uuid,
    ) : ProxyResolution()

    data object UserNotFound : ProxyResolution()

    data class Failure(val error: ProjectResolveResult.Failure) : ProxyResolution()
}

suspend fun resolveProxyTarget(db: DatabaseManager, username: String, destination: String): ProxyResolution {
    val (serviceKey, projectKey) = if ('-' in destination) destination.split('-', limit = 2).let { Pair(it[0], it[1]) }
    else null to destination

    return db.query {
        val user = User.find { Users.username.lowerCase() eq username.lowercase() }.firstOrNull()
            ?: return@query ProxyResolution.UserNotFound

        val project = Project.find {
            (Projects.projectKey.lowerCase() eq projectKey.lowercase()) and (Projects.owner eq user.id)
        }.firstOrNull() ?: return@query ProxyResolution.Failure(ProjectResolveResult.Failure.ProjectNotFound)

        val service = serviceKey?.let { key ->
            Service.find { Services.project eq project.id and (Services.serviceKey.lowerCase() eq key.lowercase()) }
                .firstOrNull() ?: return@query ProxyResolution.Failure(ProjectResolveResult.Failure.ServiceNotFound)
        }

        ProxyResolution.Success(user = user, project = project, service = service, ownerId = user.id.value)
    }
}

/**
 * Short-lived cache for [resolveProxyTarget], keyed by the full request subdomain. The lookup runs on
 * every proxied request and its result changes rarely (renames, access-state toggles), so a few
 * seconds of staleness buy a saved database transaction per request. Failures are cached too so an
 * unknown subdomain can't force a query per request.
 */
class ProxyResolutionCache(private val db: DatabaseManager) {
    private class Entry(val expiresAt: Long, val resolution: ProxyResolution)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun resolve(domain: String, username: String, destination: String): ProxyResolution {
        val now = System.currentTimeMillis()
        entries[domain]?.let { entry ->
            if (entry.expiresAt > now) return entry.resolution
            entries.remove(domain)
        }
        val resolution = resolveProxyTarget(db, username, destination)
        if (entries.size >= MAX_ENTRIES) entries.clear()
        entries[domain] = Entry(now + TTL_MS, resolution)
        return resolution
    }

    companion object {
        private const val TTL_MS = 3_000L

        /** Hard cap so unknown-subdomain floods can't grow the cache unboundedly. */
        private const val MAX_ENTRIES = 10_000
    }
}

sealed class ProjectResolveResult {
    data class Success(val project: Project, val service: Service?) : ProjectResolveResult()
    sealed class Failure : ProjectResolveResult(), SimpleError {
        data object ProjectNotFound : Failure() {
            override val message: String = "The requested project does not exist."
            override val code: String = "ERR_PROJECT_NOT_FOUND"
        }

        data object ServiceNotFound : Failure() {
            override val message: String = "The requested service does not exist for this project."
            override val code: String = "ERR_SERVICE_NOT_FOUND"
        }
    }
}

interface SimpleError {
    val message: String
    val code: String

    suspend fun respondIn(call: ApplicationCall, status: HttpStatusCode = HttpStatusCode.InternalServerError) {
        call.respond(
            status = status,
            message = buildMap {
                put("type", "error")
                put("code", code)
                put("message", message)
            }
        )
    }
}
