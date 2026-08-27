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
import io.opentelemetry.kotlin.tracing.SpanKind
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.koin.ktor.ext.inject
import plugins.recordException
import plugins.recordFailure
import plugins.span
import plugins.startChildSpan
import plugins.traceStep
import java.io.File
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
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

            call.span.setStringAttribute(SUBDOMAIN, domain)
            call.span.setStringAttribute(DESTINATION, destination)
            call.span.setStringAttribute(USER_NAME, username)

            val resolution = call.traceStep("proxy.resolve") { span ->
                resolutionCache.resolve(domain, username, destination).also {
                    if (it is ProxyResolution.Success) span.setStringAttribute(PROJECT_NAME, it.project.projectKey)
                }
            }
            if (resolution is ProxyResolution.UserNotFound) {
                call.markRequestAsWerkbankHandled()
                call.span.recordFailure("user_not_found", "No user named $username")
                call.respondText("User not found", status = HttpStatusCode.NotFound)
                return@onCall
            }
            if (resolution is ProxyResolution.Failure) {
                call.markRequestAsWerkbankHandled()
                call.span.recordFailure(resolution.error.code, resolution.error.message)
                resolution.error.respondIn(call, HttpStatusCode.NotFound)
                return@onCall
            }

            resolution as ProxyResolution.Success
            val (user, project, service) = resolution
            call.attributes[currentUser] = user
            call.attributes[targetProject] = project
            call.attributes[targetProjectOwnerId] = resolution.ownerId
            call.attributes[targetService] = service?.let { Optional.of(it) } ?: Optional.empty()
            call.span.setStringAttribute(PROJECT_ID, project.id.value.toHexString())
            call.span.setStringAttribute(PROJECT_NAME, project.projectKey)
            service?.let { call.span.setStringAttribute(SERVICE_NAME, it.serviceKey) }

            val authorizationResult = call.traceStep("proxy.authorize") { call.checkRequestAuthorization() }
            if (authorizationResult !is AuthorizationResult.Success) {
                call.markRequestAsWerkbankHandled()
                if (call.request.headers["Werkbank-No-Browser"] == "true" || !call.isLikelyBrowser()) {
                    authorizationResult as AuthorizationResult.Failure
                    call.span.recordFailure(authorizationResult.code, authorizationResult.message)
                    authorizationResult.respondIn(call, HttpStatusCode.Unauthorized)
                } else {
                    // A browser is sent through the login flow, which is a normal outcome of a
                    // protected project and not a failed request.
                    call.span.addEvent("proxy.auth.redirect")
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
            call.span.setBooleanAttribute(TUNNEL_CONNECTED, tunnel != null)
            tunnel?.let { call.span.setBooleanAttribute(TUNNEL_ALIVE, it.isAlive) }
            tunnel?.pingMs?.value?.let { call.span.setLongAttribute(TUNNEL_PING_MS, it) }

            if (tunnel == null) {
                call.respondProxyError("tunnel-not-running", "No tunnel is connected for $username", appConfig)
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

                // Outlives the call span, which ends with the 101: a proxied WebSocket lives on for as
                // long as the browser keeps it open, and its frame counts and close reason are only
                // known then.
                val wsSpan = call.startChildSpan("tunnel WS ${wsRecord.uri}", SpanKind.CLIENT)

                val wsProxy = try {
                    tunnel.startWsProxy(wsRecord)
                } catch (e: TunnelClosedException) {
                    call.application.environment.log.warn("WebSocket proxy failed for ${wsRecord.uri}: ${e.message}")
                    wsSpan.finishWith(wsRecord, e)
                    call.recordException(e)
                    // The tunnel host explains why it could not open the WebSocket (e.g. the local
                    // service is not running); a generic message would hide that from the developer.
                    call.respondText(e.message ?: "Tunnel connection closed", status = HttpStatusCode.BadGateway)
                    return@onCall
                } catch (e: TimeoutException) {
                    call.application.environment.log.warn("WebSocket proxy timed out for ${wsRecord.uri}")
                    wsSpan.finishWith(wsRecord, e)
                    call.recordException(e)
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
                                        // A raw session has no ponger of its own, and a peer that
                                        // pings without ever getting an answer hangs up eventually.
                                        is Frame.Ping -> send(Frame.Pong(frame.data))
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

                            // WebSocketUpgrade hands out a *raw* session, so this connection gets
                            // none of the ping/pong the WebSockets plugin gives the routed tunnel
                            // socket. An idle Vite HMR connection then sends nothing for minutes and
                            // every proxy, load balancer and NAT on the way to the browser drops it
                            // once its idle timeout hits — which is why this only ever bites in
                            // production and never against a local dev stack.
                            val keepAlive = launchConnectionJob(call.application, "ws-proxy-keepalive") {
                                while (true) {
                                    delay(WS_PROXY_PING_INTERVAL)
                                    send(Frame.Ping(ByteArray(0)))
                                }
                            }

                            // When either side ends, tear the other down so the connection actually closes.
                            clientToTunnel.invokeOnCompletion {
                                tunnelToClient.cancel()
                                keepAlive.cancel()
                            }
                            tunnelToClient.invokeOnCompletion {
                                clientToTunnel.cancel()
                                keepAlive.cancel()
                            }
                            // The ping loop only ends on its own when the write failed, which means
                            // the browser is gone even though no close ever arrived.
                            keepAlive.invokeOnCompletion {
                                clientToTunnel.cancel()
                                tunnelToClient.cancel()
                            }
                        }
                    } catch (e: CancellationException) {
                        // The browser going away cancels the relay; that is a normal end, not a failure.
                        throw e
                    } catch (e: Throwable) {
                        wsSpan.recordException(e)
                        throw e
                    } finally {
                        // Both relay loops ended, which also covers the hard aborts: a reset, a closed
                        // tab or the ping timeout end the client loop without a Close frame ever
                        // arriving. NonCancellable because the close still has to reach the tunnel host
                        // when we get here on an already-cancelled call.
                        withContext(NonCancellable) { wsProxy.relayClientGone() }
                        wsProxy.close()
                        val wsResult = wsProxy.snapshot.value
                        wsSpan.finishWith(wsResult)
                        requestPersistenceQueue.submit(
                            PersistJob(
                                record = wsResult,
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

                val tunnelSpan = call.startChildSpan(
                    "tunnel ${call.request.httpMethod.value} ${call.request.uri}",
                    SpanKind.CLIENT,
                )

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
                        call.respondProxyError(
                            "no-response",
                            "No response from the tunnel host within ${NO_RESPONSE_TIMEOUT.inWholeSeconds}s",
                            appConfig,
                        )
                        return@onCall
                    } catch (e: UnexpectedTunnelException) {
                        val unexpected = e.message ?: "Unexpected error"
                        // The checkpoints go on the call span as well: they are the only account of how
                        // far the tunnel host got before it broke.
                        call.span.recordCheckpoints(
                            e.checkpoints,
                            baseTimestampMs = proxyRequest.snapshot.value.sentToTunnelAt ?: System.currentTimeMillis(),
                        )
                        call.respondProxyError(
                            "unexpected-error",
                            unexpected,
                            appConfig,
                            replacements = mapOf(TUNNEL_DETAILS_PLACEHOLDER to tunnelDetailsHtml(unexpected, e.checkpoints)),
                        )
                        return@onCall
                    } catch (_: TimeoutException) {
                        call.respondProxyError("request-timeout", "The tunnel host reported a request timeout", appConfig)
                        return@onCall
                    } catch (_: TunnelClosedException) {
                        call.respondProxyError("tunnel-closed", "The tunnel closed while the request was in flight", appConfig)
                        return@onCall
                    } catch (_: ServerNotRunningException) {
                        call.respondProxyError("service-not-running", "The service is not running on the tunnel host", appConfig)
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
                    val record = proxyRequest.snapshot.value
                    tunnelSpan.finishWith(record)
                    // Hand the finished capture to the background queue and return immediately; it owns
                    // the temp body files from here and deletes them once persisted. Persisting inline
                    // would hold the client connection and a database pool slot on the request hot path.
                    requestPersistenceQueue.submit(
                        PersistJob(
                            record = record,
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
 * How often a proxied WebSocket is pinged to keep it out of the idle timeouts of whatever sits
 * between the browser and here. Well below the 60s that load balancers commonly default to.
 */
private val WS_PROXY_PING_INTERVAL = 20.seconds

/**
 * Token embedded in the unexpected-error Svelte page. The API swaps it for the server-rendered tunnel
 * details ([tunnelDetailsHtml]) before serving the page. The page disables client-side rendering so
 * hydration can't overwrite the injected markup with the original placeholder.
 */
private const val TUNNEL_DETAILS_PLACEHOLDER = "__WERKBANK_TUNNEL_DETAILS__"

/**
 * Answers the request with the proxy error [page] rendered by the web UI, and records [message] as the
 * failure on the call span — the pages are served with a 200, so a failed request would otherwise look
 * like a successful one.
 *
 * When the web UI cannot be reached the page is replaced by a plain-text response carrying the same
 * message. Without that fallback a request that failed for a well-understood reason ("no tunnel is
 * connected") is answered with a bare 500 about the proxy's own plumbing, which says nothing about
 * what actually went wrong.
 */
private suspend fun ApplicationCall.respondProxyError(
    page: String,
    message: String,
    appConfig: AppConfig,
    replacements: Map<String, String> = emptyMap(),
) {
    span.setStringAttribute(PROXY_ERROR_PAGE, page)
    recordFailure("werkbank.proxy.$page", message)

    // Everything the page needs is inside the try: looking up the project context hits the database,
    // and a failure there must not turn into a 500 either.
    try {
        val url = URLBuilder("${appConfig.localWebRoot}/proxy/error/$page")
            .applyProjectContextForErrorPage()
            .build()
        respondWebpage(url, appConfig.appDomain, replacements)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        application.environment.log.warn(
            "Could not render the proxy error page '{}' from {}: {}", page, appConfig.localWebRoot, e.message
        )
        span.addEvent("proxy.error_page.unavailable") {
            setStringAttribute("exception.message", e.message ?: e::class.simpleName ?: "unknown")
        }
        respondText(
            "$message\n\nwerkbank could not render its error page (${appConfig.localWebRoot} is unreachable).\n",
            status = HttpStatusCode.BadGateway,
        )
    }
}

private suspend fun ApplicationCall.respondWebpage(
    url: Url,
    appDomain: String,
    replacements: Map<String, String> = emptyMap(),
) {
    // `use`, because an unreachable web UI made the fetch throw and leaked the client — and with it
    // its engine's threads — on every error page that could not be rendered.
    val (status, contentType, body) = HttpClient().use { client ->
        val response = client.get(url)
        Triple(response.status, response.contentType(), response.bodyAsText())
    }

    val fixedBody = replacements.entries
        .fold(body) { acc, (token, value) -> acc.replace(token, value) }
        .withAssetBase(appDomain)

    respondText(status = status, contentType = contentType) {
        fixedBody
    }
}

/**
 * Points every root-relative URL of the injected page at the werkbank app domain.
 *
 * The page is served from the project's own subdomain, so its asset URLs — `/_app/...` in a release
 * build, Vite's `/node_modules/...`, `/@fs/...` and `/@vite/...` in development — would be requested
 * from the tunnelled project, where this proxy answers with the error page itself. Importing an HTML
 * document as a module fails, so the page never hydrates: `onMount` does not run and everything it
 * drives (status polling, the waiting indicator) stands still.
 *
 * One `<base>` covers all of them, whatever the build emits, and the `fetch("/api/...")` calls of
 * interactive error pages along with it. Assets on the app domain answer cross-origin requests from
 * project subdomains — see the CORS block in deploy/Caddyfile and `server.cors` in web/vite.config.ts.
 */
private fun String.withAssetBase(appDomain: String): String {
    // A page that sets its own base knows better than we do.
    if (BASE_ELEMENT.containsMatchIn(this)) return this
    val head = HEAD_ELEMENT.find(this) ?: return this
    val afterHead = head.range.last + 1
    return substring(0, afterHead) + "<base href=\"https://$appDomain/\">" + substring(afterHead)
}

private val HEAD_ELEMENT = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
private val BASE_ELEMENT = Regex("<base[\\s>]", RegexOption.IGNORE_CASE)

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
        // The service-not-running page names the service it could not reach; without this it only
        // ever had "unknown service" to show.
        parameters.append(
            "service_name",
            call.attributes.getOrNull(targetService)?.orElse(null)?.serviceKey ?: "null",
        )
    }

    return this
}