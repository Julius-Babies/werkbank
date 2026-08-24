package app.werkbank.app.webapp.requests

import app.werkbank.app.webapp.socket.WebAppServerMessage
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Projects
import app.werkbank.database.TunnelRequest
import app.werkbank.database.TunnelRequests
import app.werkbank.database.User
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Requests are handed out in pages of this size unless the client asks for a different one. */
const val REQUEST_PAGE_SIZE = 200

private const val MAX_REQUEST_PAGE_SIZE = 500

/**
 * A page of a user's request history, newest first. [before] pages backwards: only requests that
 * started no later than it are returned. It is compared inclusively and callers deduplicate by id,
 * because several requests can share a millisecond and an exclusive comparison would drop them.
 *
 * The history is not cut off by age: the client pages as far back as it is scrolled, down to the
 * oldest request that was ever persisted.
 */
suspend fun DatabaseManager.requestHistory(
    user: User,
    before: Instant? = null,
    limit: Int = REQUEST_PAGE_SIZE,
): List<WebAppServerMessage.RequestUpdate> = query {
    TunnelRequests
        .join(Projects, JoinType.INNER, onColumn = TunnelRequests.project, otherColumn = Projects.id)
        .select(TunnelRequests.columns)
        .where { Projects.owner eq user.id.value }
        .apply { if (before != null) andWhere { TunnelRequests.startedAt lessEq before } }
        .orderBy(TunnelRequests.startedAt, SortOrder.DESC)
        .limit(limit.coerceIn(1, MAX_REQUEST_PAGE_SIZE))
        .let { TunnelRequest.wrapRows(it) }
        .map { request -> WebAppServerMessage.RequestUpdate.from(request) }
}

/**
 * The current state of specific requests, for revalidating a client side cache. Requests that were
 * cached while still running can have finished since, and only the client knows which ones those
 * are — persisted rows are never written again, so nothing else can go stale.
 */
suspend fun DatabaseManager.requestsByIds(
    user: User,
    ids: List<Uuid>,
): List<WebAppServerMessage.RequestUpdate> = query {
    if (ids.isEmpty()) return@query emptyList()

    TunnelRequests
        .join(Projects, JoinType.INNER, onColumn = TunnelRequests.project, otherColumn = Projects.id)
        .select(TunnelRequests.columns)
        .where { Projects.owner eq user.id.value }
        .andWhere { TunnelRequests.id inList ids }
        .let { TunnelRequest.wrapRows(it) }
        .map { request -> WebAppServerMessage.RequestUpdate.from(request) }
}
