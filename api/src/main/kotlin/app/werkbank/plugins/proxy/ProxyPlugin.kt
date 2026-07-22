package app.werkbank.plugins.proxy

import app.werkbank.app.tunnel.*
import app.werkbank.config.AppConfig
import app.werkbank.database.*
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.koin.ktor.ext.inject
import java.io.File
import java.util.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

val subdomain = AttributeKey<String>("subdomain")
val requestedDestination = AttributeKey<String>("requested_destination")
val currentUser = AttributeKey<User>("user")
val targetProject = AttributeKey<Project>("project")
val targetService = AttributeKey<Optional<Service>>("service")

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
            call.attributes[subdomain] = domain

            val (destination, username) = domain.split(".", limit = 2)
            val user = db.query { User.find { Users.username.lowerCase() eq username.lowercase() }.firstOrNull() }
            call.attributes[requestedDestination] = destination

            if (user == null) {
                call.markRequestAsWerkbankHandled()
                call.respondText("User not found", status = HttpStatusCode.NotFound)
                return@onCall
            }

            call.attributes[currentUser] = user

            val destinationResolution = call.getProjectAndServiceFromRequest()
            if (destinationResolution !is ProjectResolveResult.Success) {
                call.markRequestAsWerkbankHandled()
                destinationResolution as ProjectResolveResult.Failure
                destinationResolution.respondIn(call, HttpStatusCode.NotFound)
                return@onCall
            }

            val (project, service) = destinationResolution
            call.attributes[targetProject] = project
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
                val wsProxy = try {
                    tunnel.wsProxy(
                        projectName = project.projectKey,
                        serviceName = service?.serviceKey,
                        path = call.request.uri,
                        headers = call.request.headers.toMap(),
                    )
                } catch (_: TunnelClosedException) {
                    call.respondText("Tunnel connection closed", status = HttpStatusCode.BadGateway)
                    return@onCall
                } catch (_: TimeoutException) {
                    call.respondText("WebSocket proxy timed out", status = HttpStatusCode.GatewayTimeout)
                    return@onCall
                }

                call.respond(WebSocketUpgrade(call) {
                    launchConnectionJob(call.application, "ws-proxy-client-to-tunnel") {
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

                    launchConnectionJob(call.application, "ws-proxy-tunnel-to-client") {
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
                val requestId = Uuid.random()
                val proxyScope = CoroutineScope(currentCoroutineContext())
                val tempDir = File(appConfig.storage.temporaryDir).also { it.mkdirs() }
                val requestBodyFile = if (call.request.httpMethod == HttpMethod.Get) null
                    else File(tempDir, "tunnel-req-$requestId")
                val responseBodyFile = File(tempDir, "tunnel-res-$requestId")

                tunnel.addProxyRequestRecord(
                    TunnelRequestRecord(
                        requestId = requestId,
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
                    )
                )

                try {
                    val response = try {
                        val result = tunnel.request(
                            method = call.request.httpMethod,
                            projectName = project.projectKey,
                            serviceName = service?.serviceKey,
                            path = call.request.uri,
                            headers = call.request.headers.toMap(),
                            body = when (call.request.httpMethod) {
                                HttpMethod.Get -> null
                                else -> call.receiveChannel().teeToFile(requestBodyFile!!, proxyScope)
                            },
                            coroutineScope = proxyScope,
                            requestId = requestId,
                        )
                        tunnel.updateProxyRequestRecord(requestId) {
                            it.copy(sentToTunnelAt = System.currentTimeMillis())
                        }
                        val response = result.await()
                        tunnel.updateProxyRequestRecord(requestId) {
                            it.copy(
                                responseStartedAt = System.currentTimeMillis(),
                                statusCode = response.status.value,
                                responseHeaders = response.headers,
                            )
                        }
                        response
                    } catch (_: TimeoutException) {
                        tunnel.updateProxyRequestRecord(requestId) {
                            it.copy(error = "Request timed out", completedAt = System.currentTimeMillis())
                        }
                        val url = with(call) {
                            URLBuilder("${appConfig.localWebRoot}/proxy/error/request-timeout")
                                .applyProjectContextForErrorPage()
                                .build()
                        }
                        call.respondWebpage(url, appConfig.appDomain)
                        return@onCall
                    } catch (_: TunnelClosedException) {
                        tunnel.updateProxyRequestRecord(requestId) {
                            it.copy(error = "Tunnel connection closed", completedAt = System.currentTimeMillis())
                        }
                        val url = with(call) {
                            URLBuilder("${appConfig.localWebRoot}/proxy/error/tunnel-closed")
                                .applyProjectContextForErrorPage()
                                .build()
                        }
                        call.respondWebpage(url, appConfig.appDomain)
                        return@onCall
                    } catch (_: ServerNotRunningException) {
                        tunnel.updateProxyRequestRecord(requestId) {
                            it.copy(error = "Service not running", completedAt = System.currentTimeMillis())
                        }
                        val url = with(call) {
                            URLBuilder("${appConfig.localWebRoot}/proxy/error/service-not-running")
                                .applyProjectContextForErrorPage()
                                .build()
                        }
                        call.respondWebpage(url, appConfig.appDomain)
                        return@onCall
                    }

                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val status: HttpStatusCode? get() = response.status
                        override val headers: Headers get() = Headers.build {
                            response.headers.forEach { (key, values) ->
                                values.forEach { append(key, it) }
                            }
                        }

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            try {
                                val body = response.body
                                if (body != null) {
                                    responseBodyFile.outputStream().use { out ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val read = body.readAvailable(buffer)
                                            if (read <= 0) break
                                            out.write(buffer, 0, read)
                                            channel.writeFully(buffer, 0, read)
                                        }
                                    }
                                }
                                tunnel.updateProxyRequestRecord(requestId) {
                                    it.copy(completedAt = System.currentTimeMillis())
                                }
                            } catch (e: Exception) {
                                tunnel.updateProxyRequestRecord(requestId) {
                                    it.copy(
                                        error = it.error ?: (e.message ?: "Response streaming failed"),
                                        completedAt = System.currentTimeMillis(),
                                    )
                                }
                                throw e
                            }
                        }
                    })
                } finally {
                    persistTunnelRequest(
                        db = db,
                        tunnel = tunnel,
                        requestId = requestId,
                        projectId = project.id,
                        explicitServiceId = service?.id,
                        requestBodyFile = requestBodyFile,
                        responseBodyFile = responseBodyFile,
                    )
                    requestBodyFile?.delete()
                    responseBodyFile.delete()
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

private suspend fun ApplicationCall.respondWebpage(url: Url, appDomain: String) {
    val client = HttpClient()
    val response = client.get(url)
    val contentType = response.contentType()
    val status = response.status
    val body = response.bodyAsText()
    val fixedBody = body.replace("\"/_app/", "\"https://$appDomain/_app/")

    respondText(status = status, contentType = contentType) {
        fixedBody
    }

    client.close()
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

/**
 * Persists the finished proxy request described by the in-memory record into [TunnelRequests].
 * Bodies are streamed from their temp files straight into the blob columns via [ExposedBlob], so
 * they are never fully materialised on the heap. Requests without a resolved service are skipped.
 */
private suspend fun persistTunnelRequest(
    db: DatabaseManager,
    tunnel: TunnelInstance,
    requestId: Uuid,
    projectId: EntityID<Uuid>,
    explicitServiceId: EntityID<Uuid>?,
    requestBodyFile: File?,
    responseBodyFile: File?,
) {
    val record = tunnel.proxyRequests.value.firstOrNull { it.requestId == requestId } ?: return

    // Bodies are stored exactly as they came over the tunnel, i.e. still compressed. Decode them so
    // the stored copy is the plain body and drop the Content-Encoding header that no longer applies.
    // `decode = false` reproduces the raw behaviour and is used as a fallback if decoding blows up on
    // a mislabelled body (e.g. header claims gzip but the bytes are not) — the transaction rolls back
    // so we never lose the request record over an unreadable body.
    suspend fun writeRecord(decode: Boolean) {
        // The raw file streams are the underlying resource; closing them in the finally releases the
        // fds even if a decoder constructor throws on a mislabelled body before the db insert runs.
        val rawRequestBody = requestBodyFile?.takeIf { it.isFile && it.length() > 0 }?.inputStream()
        val rawResponseBody = responseBodyFile?.takeIf { it.isFile && it.length() > 0 }?.inputStream()

        try {
            val requestBody = rawRequestBody?.let {
                if (decode) decodeHttpBody(it, record.requestHeaders.contentEncoding()) else DecodedBody(it, false)
            }
            val responseBody = rawResponseBody?.let {
                if (decode) decodeHttpBody(it, record.responseHeaders?.contentEncoding()) else DecodedBody(it, false)
            }

            db.query {
                val serviceEntity = explicitServiceId?.let { Service.findById(it) }
                    ?: record.serviceName?.let { name ->
                        Service.find {
                            (Services.project eq projectId) and (Services.serviceKey.lowerCase() eq name.lowercase())
                        }.firstOrNull()
                    }
                if (serviceEntity == null) {
                    println("Skipping tunnel request persistence for $requestId: no resolved service")
                    return@query
                }

                val statusCode = record.statusCode
                val error = record.error
                val outcome = when {
                    error != null -> TunnelRequestResult.Failure(error)
                    statusCode != null -> TunnelRequestResult.Success(statusCode)
                    else -> TunnelRequestResult.Failure("Request did not complete")
                }

                TunnelRequest.new(requestId) {
                    this.service = serviceEntity
                    this.method = record.method
                    this.uri = record.uri
                    this.requestHeaders =
                        if (requestBody?.decoded == true) record.requestHeaders.withoutBodyEncodingHeaders()
                        else record.requestHeaders
                    this.responseHeaders = record.responseHeaders?.let {
                        if (responseBody?.decoded == true) it.withoutBodyEncodingHeaders() else it
                    }
                    this.result = outcome
                    this.requestBody = requestBody?.let { ExposedBlob(it.stream) }
                    this.responseBody = responseBody?.let { ExposedBlob(it.stream) }
                    this.startedAt = Instant.fromEpochMilliseconds(record.startedAt)
                    this.responseReadyAt = record.responseStartedAt?.let { Instant.fromEpochMilliseconds(it) }
                }
            }
        } finally {
            rawRequestBody?.close()
            rawResponseBody?.close()
        }
    }

    try {
        writeRecord(decode = true)
    } catch (e: Exception) {
        println("Failed to persist decoded bodies for $requestId, storing raw instead: ${e.message}")
        writeRecord(decode = false)
    }
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