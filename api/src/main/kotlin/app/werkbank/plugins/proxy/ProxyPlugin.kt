package app.werkbank.plugins.proxy

import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.config.AppConfig
import app.werkbank.database.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

val SubdomainHandler = createApplicationPlugin(name = "SubdomainHandler") {
    val appConfig by application.inject<AppConfig>()
    val db by application.inject<DatabaseManager>()
    val tunnelManager by application.inject<TunnelManager>()

    val suffix = appConfig.domainSuffix
    val regex = Regex("(.+\\.){2}${suffix.replace(".", "\\.")}")

    onCall { call ->
        val host = call.request.host()
        if (host == appConfig.appDomain) return@onCall
        if (regex.matches(host)) {
            val domain = host.removeSuffix(".$suffix")
            val (destination, username) = domain.split(".", limit = 2)

            val user = db.query { User.find { Users.username.lowerCase() eq username.lowercase() }.firstOrNull() }
            if (user == null) {
                call.respondText("User not found", status = HttpStatusCode.NotFound)
                return@onCall
            }

            val project: Project
            val service: Service?

            if ('-' in destination) {
                val (serviceName, projectName) = destination.split('-', limit = 2)

                val requestedProject = db.query { Project.find { (Projects.name.lowerCase() eq projectName.lowercase()) and (Projects.owner eq user.id) }.firstOrNull() }
                if (requestedProject == null) {
                    call.respondText("Project not found", status = HttpStatusCode.NotFound)
                    return@onCall
                }
                project = requestedProject

                val requestedService = db.query { Service.find { Services.project eq project.id and (Services.serviceKey.lowerCase() eq serviceName.lowercase()) }.firstOrNull() }
                if (requestedService == null) {
                    call.respondText("Service not found", status = HttpStatusCode.NotFound)
                    return@onCall
                }
                service = requestedService
            } else {
                val requestedProject = db.query { Project.find { (Projects.name.lowerCase() eq destination.lowercase()) and (Projects.owner eq user.id) }.firstOrNull() }
                if (requestedProject == null) {
                    call.respondText("Project not found", status = HttpStatusCode.NotFound)
                    return@onCall
                }
                project = requestedProject
                service = null
            }

            val authCookieName = "werkbank-project-auth-token-${project.id.value.toHexString()}"
            val cookieValue = call.request.cookies[authCookieName]

            var isAuthorized = if (project.accessState == Project.AccessState.Open) true else run authorizationValidation@{
                if (cookieValue == null) return@authorizationValidation false
                else {
                    val jwtVerifier = JWT
                        .require(Algorithm.HMAC256(appConfig.jwt.secret))
                        .withAudience("werkbank-project-${project.id.value.toHexString()}")
                        .withIssuer("werkbank")
                        .build()
                    try {
                        val jwt = jwtVerifier.verify(cookieValue)
                        if (jwt.audience.first() != "werkbank-project-${project.id.value.toHexString()}") return@authorizationValidation false
                        when (jwt.getClaim("source").asString()) {
                            "user" -> {
                                val user = db.query { User.findById(Uuid.parse(jwt.getClaim("user_id").asString())) }
                                if (user == null) return@authorizationValidation false
                                val isOwner = db.query { project.owner.id.value == user.id.value }
                                if (isOwner) return@authorizationValidation true
                                return@authorizationValidation false
                            }
                            "password" -> {
                                if (project.accessState == Project.AccessState.Disabled) return@authorizationValidation false
                                val passwordId = jwt.getClaim("password_id").asString()
                                val projectUsesPassword = db.query { project.passwords.any { password -> password.password.id.value.toHexString() == passwordId } }
                                return@authorizationValidation projectUsesPassword
                            }
                        }
                    } catch (_: JWTVerificationException) {
                        return@authorizationValidation false
                    }
                }

                return@authorizationValidation null
            }

            if (isAuthorized == null) {
                call.respondText(
                    "Something went wrong, we couldn't determine whether this request is authorized. We're sorry for the inconvenience. This is a bug, please report it to us.",
                    status = HttpStatusCode.InternalServerError
                )
                return@onCall
            }

            if (!isAuthorized) {

                val proxyAuthSession = ProxyAuthSession(
                    path = call.request.uri,
                    project = project,
                    host = call.request.host(),
                    headers = call.request.headers.entries().flatMap { entry -> entry.value.map { "${entry.key}=$it" } }
                )

                val authSessionId = Uuid.random()
                proxyAuthSessions[authSessionId] = proxyAuthSession

                val url = URLBuilder("https://${appConfig.appDomain}/api/proxy/auth/landing").apply {
                    parameters.append("proxy_auth_session_id", authSessionId.toString())
                }
                call.respondRedirect(url.build(), permanent = false)
                return@onCall
            }

            require(isAuthorized) { "isAuthorized should be true" }

            val isWebSocket = call.request.httpMethod == HttpMethod.Get &&
                call.request.headers["Upgrade"]?.lowercase() == "websocket"

            val tunnel = tunnelManager.getTunnel(user)

            if (tunnel == null) {
                call.respondText("Tunnel not active, start with wb tunnel.", status = HttpStatusCode.ServiceUnavailable)
                return@onCall
            }

            if (isWebSocket) {
                val wsProxy = tunnel.wsProxy(
                    projectName = project.projectKey,
                    serviceName = service?.serviceKey,
                    path = call.request.uri,
                    headers = call.request.headers.toMap(),
                )

                call.respond(WebSocketUpgrade(call) {
                    launch {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> wsProxy.send(Frame.Text(frame.readText()))
                                is Frame.Binary -> wsProxy.send(Frame.Binary(true, frame.readBytes()))
                                is Frame.Close -> {
                                    wsProxy.send(frame.readReason()?.let { Frame.Close(it) } ?: Frame.Close())
                                    break
                                }
                                else -> {}
                            }
                        }
                    }

                    launch {
                        for (frame in wsProxy.incomingFrames) {
                            when (frame) {
                                is Frame.Text -> send(Frame.Text(frame.readText()))
                                is Frame.Binary -> send(Frame.Binary(true, frame.readBytes()))
                                is Frame.Close -> {
                                    close(frame.readReason() ?: CloseReason(1000, ""))
                                    break
                                }
                                else -> {}
                            }
                        }
                    }
                })
            } else {
                val result = tunnel.request(
                    method = call.request.httpMethod,
                    projectName = project.projectKey,
                    serviceName = service?.serviceKey,
                    path = call.request.uri,
                    headers = call.request.headers.toMap(),
                    body = when (call.request.httpMethod) {
                        HttpMethod.Get -> null
                        else -> call.receiveChannel()
                    },
                    coroutineScope = CoroutineScope(currentCoroutineContext())
                )


                val response = result.await()
                response.headers.forEach { (key, values) ->
                    values.forEach { call.response.headers.append(key, it) }
                }
                call.respondOutputStream(
                    status = response.status,
                    producer = { response.body?.copyTo(this) }
                )
            }
        }
    }
}

val proxyAuthSessions = mutableMapOf<Uuid, ProxyAuthSession>()

data class ProxyAuthSession(
    val path: String,
    val project: Project,
    val host: String,
    val headers: List<String>,
)