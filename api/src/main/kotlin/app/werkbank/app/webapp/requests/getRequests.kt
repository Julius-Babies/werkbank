package app.werkbank.app.webapp.requests

import app.werkbank.app.webapp.socket.WebAppServerMessage
import app.werkbank.database.*
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

fun Route.getRequests() {

    val db by inject<DatabaseManager>()

    authenticate(AUTH_USER_JWT) {
        get {
            val principal = call.principal<UserPrincipal>()!!

            val requests = db.query {
                TunnelRequests
                    .join(Projects, JoinType.INNER, onColumn = TunnelRequests.project, otherColumn = Projects.id)
                    .select(TunnelRequests.columns)
                    .where { Projects.owner eq principal.user.id.value }
                    .andWhere { TunnelRequests.startedAt greaterEq Clock.System.now() - 8.hours }
                    .orderBy(TunnelRequests.startedAt, SortOrder.DESC)
                    .let { TunnelRequest.wrapRows(it) }
                    .map { request -> WebAppServerMessage.RequestUpdate.from(request) }
            }

            call.respond(requests)
        }
    }
}