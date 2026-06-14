package app.werkbank.app.proxy.auth

import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Project
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.plugins.proxy.proxyAuthSessions
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

fun Route.proxyAuthLanding() {
    val db by inject<DatabaseManager>()
    val appConfig by inject<AppConfig>()

    authenticate("jwt", optional = true) {
        get {
            val principal = call.principal<UserPrincipal>()

            val proxyAuthSessionId = call.request.queryParameters["proxy_auth_session_id"]?.let { Uuid.parse(it) } ?: return@get
            val proxyAuthSession = proxyAuthSessions[proxyAuthSessionId] ?: return@get call.respond(message = "Session not found", status = HttpStatusCode.NotFound)
            val project = proxyAuthSession.project

            if (project.accessState == Project.AccessState.Disabled) {
                call.respondText("Project access is disabled for anonymous users. Please log in to access this project.", status = HttpStatusCode.Unauthorized)
                return@get
            }

            val canUsePasswordAuth = !db.query { project.passwords.empty() }

            suspend fun buildPasswordRedirect (isWrongUserLoggedIn: Boolean): Url {
                return db.query {
                    URLBuilder("https://${appConfig.appDomain}/proxy/auth/login/password").apply {
                        parameters.append("project_id", project.id.value.toHexString())
                        parameters.append("project_name", project.name)
                        parameters.append("proxy_auth_session_id", proxyAuthSessionId.toHexString())
                        parameters.append("owner_id", project.owner.id.value.toHexString())
                        parameters.append("owner_avatar_url", project.owner.profileImageUrl ?: "null")
                        parameters.append("owner_username", project.owner.username)
                        parameters.append("is_wrong_user_logged_in", isWrongUserLoggedIn.toString())
                    }
                }.build()
            }

            if (principal != null) {
                db.query {
                    if (project.owner.id.value == principal.user.id.value) {
                        val tokenValidity = 60.days
                        val token = JWT.create()
                            .withAudience("werkbank-project-${project.id.value.toHexString()}")
                            .withIssuer("werkbank")
                            .withClaim("source", "owner")
                            .withClaim("project_id", project.id.value.toHexString())
                            .withExpiresAt((Clock.System.now() + tokenValidity).toJavaInstant())
                            .sign(Algorithm.HMAC256(appConfig.jwt.secret))

                        tokenMap[proxyAuthSessionId] = token

                        val userDomain = principal.user.username.lowercase() + "." + appConfig.domainSuffix
                        call.respondRedirect("https://$userDomain/api/proxy/auth/result?proxy_auth_session_id=$proxyAuthSessionId")
                        return@query
                    } else {
                        if (canUsePasswordAuth) {
                            call.respondRedirect(buildPasswordRedirect(isWrongUserLoggedIn = false))
                        } else {
                            call.respond(
                                message = "Wrong user account, password access is disabled. Log in as a permitted user to access this project.",
                                status = HttpStatusCode.Unauthorized
                            )
                            return@query
                        }
                    }
                }
            } else {
                if (!canUsePasswordAuth) {
                    call.respond(
                        message = "Not authenticated, password access is disabled. Log in as a permitted user to access this project.",
                        status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                call.respondRedirect(buildPasswordRedirect(isWrongUserLoggedIn = false))
                return@get
            }
        }
    }
}

val tokenMap = mutableMapOf<Uuid, String>()