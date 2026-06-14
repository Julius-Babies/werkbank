package app.werkbank.app.proxy.auth

import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Project
import app.werkbank.database.User
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.plugins.proxy.proxyAuthSessions
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import org.koin.mp.KoinPlatform.getKoin
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

val MAX_ACCESS_TOKEN_VALIDITY = 60.days

fun Route.proxyAuthLanding() {
    val db by inject<DatabaseManager>()
    val appConfig by inject<AppConfig>()

    authenticate("jwt", optional = true) {
        get {
            val principal = call.principal<UserPrincipal>()

            val proxyAuthSessionId =
                call.request.queryParameters["proxy_auth_session_id"]?.let { Uuid.parse(it) } ?: return@get
            val proxyAuthSession = proxyAuthSessions[proxyAuthSessionId] ?: return@get call.respond(
                message = "Session not found",
                status = HttpStatusCode.NotFound
            )
            val project = proxyAuthSession.project

            when (project.accessState) {
                Project.AccessState.Disabled -> {
                    /**
                     * Only the owner is permitted to access the project.
                     */
                    val isPrincipalProjectOwner =
                        principal != null && db.query { project.owner.id.value == principal.user.id.value }

                    if (isPrincipalProjectOwner) {
                        val token = generateJwtForUserAndProject(principal.user, project)

                        tokenMap[proxyAuthSessionId] = token

                        val userDomain = principal.user.username.lowercase() + "." + appConfig.domainSuffix
                        call.respondRedirect("https://$userDomain/api/proxy/auth/result?proxy_auth_session_id=$proxyAuthSessionId")
                        return@get
                    } else {
                        val url = db.query {
                            URLBuilder("https://${appConfig.appDomain}/proxy/auth/account-required").apply {
                                parameters.append("project_id", project.id.value.toHexString())
                                parameters.append("project_name", project.name)
                                parameters.append("owner_id", project.owner.id.value.toHexString())
                                parameters.append("owner_avatar_url", project.owner.profileImageUrl ?: "null")
                                parameters.append("owner_username", project.owner.username)
                                parameters.append("is_wrong_user_logged_in", (principal != null).toString())

                                val wbCloudAuthUrl = URLBuilder("https://${appConfig.appDomain}/api/login").apply {
                                    parameters.append(
                                        "redirect",
                                        "https://${proxyAuthSession.host}${proxyAuthSession.path}"
                                    )
                                }

                                parameters.append("wbcloud_auth_url", wbCloudAuthUrl.buildString())
                            }
                        }.build()
                        call.respondRedirect(url, permanent = false)
                        return@get
                    }
                }

                Project.AccessState.Restricted -> {

                    /**
                     * Only the owner or invited users are permitted to access the project. Currently, inviting users is not supported.
                     * Also, passwords can be used instead.
                     */

                    val isUserAllowed =
                        principal != null && db.query { project.owner.id.value == principal.user.id.value }
                    if (isUserAllowed) {
                        val token = generateJwtForUserAndProject(principal.user, project)

                        tokenMap[proxyAuthSessionId] = token

                        val userDomain = principal.user.username.lowercase() + "." + appConfig.domainSuffix
                        call.respondRedirect("https://$userDomain/api/proxy/auth/result?proxy_auth_session_id=$proxyAuthSessionId")
                        return@get
                    }

                    val canUsePasswordAuth = !db.query { project.passwords.empty() }
                    if (canUsePasswordAuth) {
                        val url = db.query {
                            URLBuilder("https://${appConfig.appDomain}/proxy/auth/login/password").apply {
                                parameters.append("project_id", project.id.value.toHexString())
                                parameters.append("project_name", project.name)
                                parameters.append("proxy_auth_session_id", proxyAuthSessionId.toHexString())
                                parameters.append("owner_id", project.owner.id.value.toHexString())
                                parameters.append("owner_avatar_url", project.owner.profileImageUrl ?: "null")
                                parameters.append("owner_username", project.owner.username)
                                parameters.append("is_wrong_user_logged_in", (principal != null).toString())
                                val wbCloudAuthUrl = URLBuilder("https://${appConfig.appDomain}/api/login").apply {
                                    parameters.append(
                                        "redirect",
                                        "https://${proxyAuthSession.host}${proxyAuthSession.path}"
                                    )
                                }

                                parameters.append("wbcloud_auth_url", wbCloudAuthUrl.buildString())
                            }
                        }.build()
                        call.respondRedirect(url)
                        return@get
                    }

                    call.respond(
                        message = "Wrong user account, password access is disabled. Log in as a permitted user to access this project.",
                        status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                Project.AccessState.Open -> {
                    call.respondRedirect("https://${proxyAuthSession.host}${proxyAuthSession.path}", permanent = false)
                    return@get
                }
            }
        }
    }
}

val tokenMap = mutableMapOf<Uuid, String>()

private suspend fun generateJwtForUserAndProject(user: User, project: Project): String {
    val db = getKoin().get<DatabaseManager>()
    val appConfig = getKoin().get<AppConfig>()

    return db.query {
        JWT.create()
            .withAudience("werkbank-project-${project.id.value.toHexString()}")
            .withIssuer("werkbank")
            .withClaim("source", "user")
            .withClaim("user_id", user.id.value.toHexString())
            .withClaim("project_id", project.id.value.toHexString())
            .withExpiresAt((Clock.System.now() + MAX_ACCESS_TOKEN_VALIDITY).toJavaInstant())
            .sign(Algorithm.HMAC256(appConfig.jwt.secret))
    }
}