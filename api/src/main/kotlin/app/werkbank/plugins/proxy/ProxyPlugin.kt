package app.werkbank.plugins.proxy

import app.werkbank.app.queue.request.PersistJob
import app.werkbank.app.queue.request.RequestPersistenceQueue
import app.werkbank.app.tunnel.*
import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.Project
import app.werkbank.database.Service
import app.werkbank.database.User
import app.werkbank.shared.tunnel.TunnelCheckpoint
import app.werkbank.util.isLikelyBrowser
import app.werkbank.util.launchConnectionJob
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.websocket.*
import io.ktor.util.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.koin.ktor.ext.inject
import java.io.File
import java.util.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

val subdomain = AttributeKey<String>("subdomain")
val requestedDestination = AttributeKey<String>("requested_destination")
val currentUser = AttributeKey<User>("user")
val targetProject = AttributeKey<Project>("project")
val targetProjectOwnerId = AttributeKey<Uuid>("project_owner_id")
val targetService = AttributeKey<Optional<Service>>("service")

val SubdomainHandler = createApplicationPlugin(name = "SubdomainHandler") {
    val appConfig by application.inject<AppConfig>()
    val db by application.inject<DatabaseManager>()
    val tunnelManager by application.inject<TunnelManager>()
    val requestPersistenceQueue by application.inject<RequestPersistenceQueue>()
    val resolutionCache = ProxyResolutionCache(db)

    val suffix = appConfig.domainSuffix
    val regex = Regex("(.+\\.){2}${suffix.replace(".", "\\.")}")

    onCall { call ->
        val host = call.request.host()
        if (host == appConfig.appDomain) return@onCall
        if (regex.matches(host)) {
            val domain = host.removeSuffix(".$suffix")
            call.attributes[subdomain] = domain

            val (destination, username) = domain.split(".", limit = 2)
            call.attributes[requestedDestination] = destination

            val resolution = resolutionCache.resolve(domain, username, destination)
            if (resolution is ProxyResolution.UserNotFound) {
                call.markRequestAsWerkbankHandled()
                call.respondText("User not found", status = HttpStatusCode.NotFound)
                return@onCall
            }
            if (resolution is ProxyResolution.Failure) {
                call.markRequestAsWerkbankHandled()
                resolution.error.respondIn(call, HttpStatusCode.NotFound)
                return@onCall
            }

            resolution as ProxyResolution.Success
            val (user, project, service) = resolution
            call.attributes[currentUser] = user
            call.attributes[targetProject] = project
            call.attributes[targetProjectOwnerId] = resolution.ownerId
            call.attributes[targetService] = service?.let { Optional.of(it) } ?: Optional.empty()

            val authorizationResult = call.checkRequestAuthorization()
            if (authorizationResult !is AuthorizationResult.Success) {
                call.markRequestAsWerkbankHandled()
                if (call.request.headers["Werkbank-No-Browser"] == "true" || !call.isLikelyBrowser()) {
                    authorizationResult as AuthorizationResult.Failure
                    authorizationResult.respondIn(call, HttpStatusCode.Unauthorized)
                } else {
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
                }
                return@onCall
            }

            val isWebSocket = call.request.httpMethod == HttpMethod.Get &&
                call.request.headers["Upgrade"]?.lowercase() == "websocket"

            val tunnel = tunnelManager.getTunnel(user)

            if (tunnel == null) {
                val url = with(call) {
                    URLBuilder("${appConfig.localWebRoot}/proxy/error/tunnel-not-running")
                        .applyProjectContextForErrorPage()
                        .build()
                }
                call.respondWebpage(url, appConfig.appDomain)
                return@onCall
            }

            if (isWebSocket) {
                val requestId = Uuid.random()
                val wsRecord = TunnelRequestRecord(
                    requestId = requestId,
                    kind = RequestKind.WEBSOCKET,
                    method = call.request.httpMethod.value,
                    uri = call.request.uri,
                    projectId = project.id.value.toHexString(),
                    projectName = project.projectKey,
                    serviceName = service?.serviceKey,
                    requestHeaders = call.request.headers.toMap(),
                    responseHeaders = null,
                    statusCode = null,
                    error = null,
                    startedAt = System.currentTimeMillis(),
                    sentToTunnelAt = null,
                    responseStartedAt = null,
                    completedAt = null,
                    requestBodyPath = null,
                    responseBodyPath = null,
                )

                val wsProxy = try {
                    tunnel.startWsProxy(wsRecord)
                } catch (e: TunnelClosedException) {
                    call.application.environment.log.warn("WebSocket proxy failed for ${wsRecord.uri}: ${e.message}")
                    call.respondText("Tunnel connection closed", status = HttpStatusCode.BadGateway)
                    return@onCall
                } catch (_: TimeoutException) {
                    call.application.environment.log.warn("WebSocket proxy timed out for ${wsRecord.uri}")
                    call.respondText("WebSocket proxy timed out", status = HttpStatusCode.GatewayTimeout)
                    return@onCall
                }

                // Echo the requested subprotocol back in the 101; browsers (e.g. Vite HMR's "vite-hmr")
                // abort the handshake if the subprotocol they offered is not confirmed.
                val requestedSubprotocol = call.request.headers["Sec-WebSocket-Protocol"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.firstOrNull { it.isNotEmpty() }

                call.respond(WebSocketUpgrade(call, protocol = requestedSubprotocol) {
                    try {
                        coroutineScope {
                            val clientToTunnel = launchConnectionJob(call.application, "ws-proxy-client-to-tunnel") {
                                for (frame in incoming) {
                                    when (frame) {
                                        is Frame.Text -> wsProxy.send(Frame.Text(frame.readText()))
                                        is Frame.Binary -> wsProxy.send(Frame.Binary(frame.fin, frame.readBytes()))
                                        is Frame.Close -> {
                                            val reason = frame.readReason() ?: CloseReason(1000, "")
                                            wsProxy.send(Frame.Close(reason))
                                            close(reason)
                                            break
                                        }
                                        else -> {}
                                    }
                                }
                            }

                            val tunnelToClient = launchConnectionJob(call.application, "ws-proxy-tunnel-to-client") {
                                for (frame in wsProxy.incomingFrames) {
                                    when (frame) {
                                        is Frame.Text -> send(Frame.Text(frame.readText()))
                                        // The bridge hands out reassembled messages, so fin is always
                                        // set here; relay it instead of hardcoding it so a fragment can
                                        // never silently go out as a complete message.
                                        is Frame.Binary -> send(Frame.Binary(frame.fin, frame.readBytes()))
                                        is Frame.Close -> {
                                            close(frame.readReason() ?: CloseReason(1000, ""))
                                            break
                                        }
                                        else -> {}
                                    }
                                }
                            }

                            // When either side ends, tear the other down so the connection actually closes.
                            clientToTunnel.invokeOnCompletion { tunnelToClient.cancel() }
                            tunnelToClient.invokeOnCompletion { clientToTunnel.cancel() }
                        }
                    } finally {
                        // Both relay loops ended, which also covers the hard aborts: a reset, a closed
                        // tab or the ping timeout end the client loop without a Close frame ever
                        // arriving. NonCancellable because the close still has to reach the tunnel host
                        // when we get here on an already-cancelled call.
                        withContext(NonCancellable) { wsProxy.relayClientGone() }
                        wsProxy.close()
                        requestPersistenceQueue.submit(
                            PersistJob(
                                record = wsProxy.snapshot.value,
                                projectId = project.id,
                                explicitServiceId = service?.id,
                                requestBodyFile = null,
                                responseBodyFile = null,
                                frames = wsProxy.framesSnapshot(),
                            )
                        )
                    }
                })
            } else {
                val requestId = Uuid.random()
                val proxyScope = CoroutineScope(currentCoroutineContext())
                val tempDir = File(appConfig.storage.temporaryDir).also { it.mkdirs() }
                val requestBodyFile = if (call.request.httpMethod == HttpMethod.Get) null
                    else File(tempDir, "tunnel-req-$requestId")
                val responseBodyFile = File(tempDir, "tunnel-res-$requestId")

                val proxyRequest = tunnel.startRequest(
                    TunnelRequestRecord(
                        requestId = requestId,
                        kind = RequestKind.HTTP,
                        method = call.request.httpMethod.value,
                        uri = call.request.uri,
                        projectId = project.id.value.toHexString(),
                        projectName = project.projectKey,
                        serviceName = service?.serviceKey,
                        requestHeaders = call.request.headers.toMap(),
                        responseHeaders = null,
                        statusCode = null,
                        error = null,
                        startedAt = System.currentTimeMillis(),
                        sentToTunnelAt = null,
                        responseStartedAt = null,
                        completedAt = null,
                        requestBodyPath = requestBodyFile?.path,
                        responseBodyPath = responseBodyFile.path,
                    ),
                    proxyScope,
                )

                try {
                    val response = try {
                        proxyRequest.send(
                            body = when (call.request.httpMethod) {
                                HttpMethod.Get -> null
                                else -> call.receiveChannel().teeToFile(requestBodyFile!!, proxyScope)
                            },
                        )
                        // Give up if the tunnel host sends no response at all within the timeout. The
                        // window only covers the wait for the status line: awaitResponse returns the
                        // moment the response headers arrive, so a slow-streaming body is never cut off.
                        withTimeout(NO_RESPONSE_TIMEOUT) { proxyRequest.awaitResponse() }
                    } catch (_: TimeoutCancellationException) {
                        proxyRequest.fail(Exception("No response from the tunnel host within ${NO_RESPONSE_TIMEOUT.inWholeSeconds}s"))
                        val url = with(call) {
                            URLBuilder("${appConfig.localWebRoot}/proxy/error/no-response")
                                .applyProjectContextForErrorPage()
                                .build()
                        }
                        call.respondWebpage(url, appConfig.appDomain)
                        return@onCall
                    } catch (e: UnexpectedTunnelException) {
                        val url = with(call) {
                            URLBuilder("${appConfig.localWebRoot}/proxy/error/unexpected-error")
                                .applyProjectContextForErrorPage()
                                .build()
                        }
                        call.respondWebpage(
                            url,
                            appConfig.appDomain,
                            replacements = mapOf(TUNNEL_DETAILS_PLACEHOLDER to tunnelDetailsHtml(e.message ?: "Unexpected error", e.checkpoints)),
                        )
                        return@onCall
                    } catch (_: TimeoutException) {
                        val url = with(call) {
                            URLBuilder("${appConfig.localWebRoot}/proxy/error/request-timeout")
                                .applyProjectContextForErrorPage()
                                .build()
                        }
                        call.respondWebpage(url, appConfig.appDomain)
                        return@onCall
                    } catch (_: TunnelClosedException) {
                        val url = with(call) {
                            URLBuilder("${appConfig.localWebRoot}/proxy/error/tunnel-closed")
                                .applyProjectContextForErrorPage()
                                .build()
                        }
                        call.respondWebpage(url, appConfig.appDomain)
                        return@onCall
                    } catch (_: ServerNotRunningException) {
                        val url = with(call) {
                            URLBuilder("${appConfig.localWebRoot}/proxy/error/service-not-running")
                                .applyProjectContextForErrorPage()
                                .build()
                        }
                        call.respondWebpage(url, appConfig.appDomain)
                        return@onCall
                    }

                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val status: HttpStatusCode get() = response.status
                        override val headers: Headers get() = Headers.build {
                            response.headers.forEach { (key, values) ->
                                values.forEach { append(key, it) }
                            }
                        }

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            try {
                                val body = response.body ?: return
                                // The capture copy goes to disk on a side channel so a slow disk write
                                // never stalls the bytes streaming to the browser. Bounded capacity so
                                // a huge body can't pile up in memory; joined before returning because
                                // the persistence queue reads the file right after this call completes.
                                val fileChunks = Channel<ByteArray>(capacity = 64)
                                val fileWriter = proxyScope.launch(Dispatchers.IO) {
                                    responseBodyFile.outputStream().use { out ->
                                        for (chunk in fileChunks) out.write(chunk)
                                    }
                                }
                                try {
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val read = body.readAvailable(buffer)
                                        if (read <= 0) break
                                        fileChunks.send(buffer.copyOf(read))
                                        channel.writeFully(buffer, 0, read)
                                    }
                                    fileChunks.close()
                                    fileWriter.join()
                                } catch (e: Exception) {
                                    fileChunks.close(e)
                                    fileWriter.cancel()
                                    throw e
                                }
                            } catch (e: Exception) {
                                proxyRequest.fail(e)
                                throw e
                            }
                        }
                    })
                } finally {
                    // Hand the finished capture to the background queue and return immediately; it owns
                    // the temp body files from here and deletes them once persisted. Persisting inline
                    // would hold the client connection and a database pool slot on the request hot path.
                    requestPersistenceQueue.submit(
                        PersistJob(
                            record = proxyRequest.snapshot.value,
                            projectId = project.id,
                            explicitServiceId = service?.id,
                            requestBodyFile = requestBodyFile,
                            responseBodyFile = responseBodyFile,
                        )
                    )
                }
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

/** How long the proxy waits for the tunnel host to start responding before giving up (no-response page). */
private val NO_RESPONSE_TIMEOUT = 30.seconds

/**
 * Token embedded in the unexpected-error Svelte page. The API swaps it for the server-rendered tunnel
 * details ([tunnelDetailsHtml]) before serving the page. The page disables client-side rendering so
 * hydration can't overwrite the injected markup with the original placeholder.
 */
private const val TUNNEL_DETAILS_PLACEHOLDER = "__WERKBANK_TUNNEL_DETAILS__"

private suspend fun ApplicationCall.respondWebpage(
    url: Url,
    appDomain: String,
    replacements: Map<String, String> = emptyMap(),
) {
    val client = HttpClient()
    val response = client.get(url)
    val contentType = response.contentType()
    val status = response.status
    val body = response.bodyAsText()
    val fixedBody = replacements.entries
        .fold(body) { acc, (token, value) -> acc.replace(token, value) }
        .replace("\"/_app/", "\"https://$appDomain/_app/")

    respondText(status = status, contentType = contentType) {
        fixedBody
    }

    client.close()
}

private fun String.escapeHtml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** Renders the tunnel-host error message and its checkpoint timeline as HTML for the error page. */
private fun tunnelDetailsHtml(message: String, checkpoints: List<TunnelCheckpoint>): String = buildString {
    append("<div class=\"font-medium text-red-800\">")
    append(message.escapeHtml())
    append("</div>")
    if (checkpoints.isNotEmpty()) {
        append("<ol class=\"mt-4 flex flex-col gap-1 font-mono text-sm text-gray-700\">")
        checkpoints.forEach { checkpoint ->
            append("<li class=\"flex flex-row gap-3\">")
            append("<span class=\"text-gray-400 tabular-nums\">+")
            append(checkpoint.elapsedMs.toString())
            append("ms</span><span>")
            append(checkpoint.label.escapeHtml())
            append("</span></li>")
        }
        append("</ol>")
    }
}

/**
 * Returns a [ByteReadChannel] that relays this channel's bytes unchanged while streaming a copy
 * into [file]. The copy runs in [scope] so the body is never buffered fully in memory.
 */
private fun ByteReadChannel.teeToFile(file: File, scope: CoroutineScope): ByteReadChannel {
    val source = this
    return scope.writer(Dispatchers.IO) {
        try {
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.readAvailable(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    channel.writeFully(buffer, 0, read)
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }.channel
}

context(call: ApplicationCall)
suspend fun URLBuilder.applyProjectContextForErrorPage(): URLBuilder {
    val db by call.inject<DatabaseManager>()
    val project = call.attributes[targetProject]
    db.query {
        parameters.append("project_id", project.id.value.toHexString())
        parameters.append("owner_id", project.owner.id.value.toHexString())
        parameters.append("owner_avatar_url", project.owner.profileImageUrl ?: "null")
        parameters.append("owner_username", project.owner.username)
    }

    return this
}