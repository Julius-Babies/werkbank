package commands.tunnel

import app.config.MainConfig
import app.config.WerkbankConfig
import app.repository.ProjectRepository
import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.ServerMessage
import app.werkbank.shared.tunnel.base64Chunks
import app.werkbank.shared.tunnel.json
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import es.jvbabi.docker.kt.docker.DockerClient
import http.httpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.toMap
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.io.encoding.Base64
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TunnelCommand : SuspendingCliktCommand("tunnel"), KoinComponent {

    private val mainConfig by inject<MainConfig>()
    private val projectRepository by inject<ProjectRepository>()
    private val dockerClient by inject<DockerClient>()

    override suspend fun run() {
        val authToken = mainConfig.getConfig().auth?.bearer ?: run {
            println(buildStyledString {
                red { +"You are not logged into your Werkbank cloud account." }
                +" "
                +"Use "
                blue { +"wb login" }
                +" to log in."
            })
            exitProcess(1)
        }

        val requestBodies = mutableMapOf<Uuid, ByteWriteChannel>()
        val wsProxyState = mutableMapOf<Uuid, DefaultClientWebSocketSession>()

        while (true) {
            try {
                httpClient().webSocket(
                    urlString = "wss://${mainConfig.getConfig().werkbankCloudDomain}/api/tunnel",
                    request = {
                        bearerAuth(authToken)
                    }
                ) serverSession@{

                    println(buildStyledString { green { +"Connected to your werkbank cloud tunnel" } })
                    for (message in incoming) {
                        if (message is Frame.Text) {
                            when (val message = json.decodeFromString<ServerMessage>(message.readText())) {
                                is ServerMessage.HttpRequest -> {
                                    launch {
                                        val channel: ByteWriteChannel?
                                        if (message.method != "GET") {
                                            channel = ByteChannel(autoFlush = true)
                                            requestBodies[message.requestId] = channel
                                        } else {
                                            channel = null
                                        }

                                        val project = projectRepository.getById(message.project)
                                        if (project == null) {
                                            println(buildStyledString {
                                                red { +"Project ${message.project} not found in config" }
                                            })
                                            return@launch
                                        }

                                        val services = project.getWerkbankConfig().services
                                        val service: WerkbankConfig.Project.Service?
                                        if (message.service != null) {
                                            val requestedService = services.firstOrNull { service -> service.name == message.service }
                                            if (requestedService == null) {
                                                println(buildStyledString {
                                                    red { +"Service ${message.service} not found in project ${message.project}" }
                                                })
                                                return@launch
                                            }
                                            service = requestedService
                                        } else {
                                            val httpRules = project.getConfig().http
                                            val requestedService = httpRules.firstNotNullOfOrNull { rule ->
                                                if (rule.pathPrefixes.none { message.path.startsWith(it) }) return@firstNotNullOfOrNull null
                                                return@firstNotNullOfOrNull services.firstOrNull { it.name == rule.targetService }
                                            }
                                            if (requestedService == null) {
                                                println(buildStyledString {
                                                    red { +"No service found for path ${message.path} in project ${message.project}" }
                                                })
                                                return@launch
                                            }
                                            service = requestedService
                                        }

                                        val serviceState = service.serviceState
                                        val targetUrl: String
                                        when (serviceState) {
                                            WerkbankConfig.Project.Service.ServiceState.Disabled -> {
                                                println(buildStyledString {
                                                    red { +"Service ${service.name} is disabled in project ${message.project}" }
                                                })
                                                return@launch
                                            }
                                            WerkbankConfig.Project.Service.ServiceState.Local -> {
                                                val port = project.getConfig().services.first { it.name == service.name }.modes.local?.port
                                                if (port == null) {
                                                    println(buildStyledString {
                                                        red { +"Service ${service.name} has no local port" }
                                                    })
                                                    return@launch
                                                }
                                                targetUrl = "http://localhost:$port${message.path}"
                                            }
                                            WerkbankConfig.Project.Service.ServiceState.Docker -> {
                                                val dockerConfig = project.getConfig().services.first { it.name == service.name }.modes.docker
                                                if (dockerConfig == null) {
                                                    println(buildStyledString {
                                                        red { +"Service ${service.name} has no docker configuration" }
                                                    })
                                                    return@launch
                                                }
                                                val container = project.getContainers().firstOrNull { it.name == dockerConfig.container }?.container
                                                if (container == null) {
                                                    println(buildStyledString {
                                                        red { +"Service ${service.name} has no docker container" }
                                                    })
                                                    return@launch
                                                }

                                                targetUrl = "http://${dockerClient.containers.inspectContainer(container.getId()!!).networkSettings.networks.values.first().ipAddress}:${dockerConfig.port}${message.path}"
                                            }
                                        }

                                        println(buildStyledString {
                                            blue {
                                                val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                                                +now.format(LocalDateTime.Format {
                                                    hour(Padding.ZERO)
                                                    char(':')
                                                    minute(Padding.ZERO)
                                                    char(':')
                                                    second(Padding.ZERO)
                                                })
                                            }
                                            + " "
                                            fun String.methodPadding() = " ${this.uppercase().padEnd(6, ' ')} "
                                            when (message.method.uppercase()) {
                                                "GET" -> bgGreen { black { +message.method.methodPadding() } }
                                                "POST" -> bgYellow { black { +message.method.methodPadding() } }
                                                "PUT" -> bgBlue { white { +message.method.methodPadding() } }
                                                "PATCH" -> bgPurple { white { +message.method.methodPadding() } }
                                                "DELETE" -> bgRed { white { +message.method.methodPadding() } }
                                                "HEAD" -> bgGray { white { +message.method.methodPadding() } }
                                                "OPTIONS" -> bgGray { white { +message.method.methodPadding() } }
                                                else -> +message.method.methodPadding()
                                            }
                                            +" "
                                            bold {
                                                +project.id
                                                +"."
                                                italic { +service.name }
                                            }
                                            +" / "
                                            +message.path.removePrefix("/")
                                            +"->"
                                            +" "
                                            gray { +targetUrl }
                                        })

                                        val request = httpClient().prepareRequest(
                                            urlString = targetUrl,
                                        ) {
                                            method = HttpMethod(message.method)
                                            message.headers.forEach { header ->
                                                val (key, value) = header.split(": ", limit = 2)
                                                headers.append(key, value)
                                            }
                                            if (channel != null) setBody(channel)
                                        }

                                        val response = request.execute()
                                        sendSerialized<ClientMessage>(ClientMessage.HttpResponse(
                                            requestId = message.requestId,
                                            statusCode = response.status.value,
                                            headers = response.headers.toMap().flatMap { (key, values) ->
                                                values.map { "$key: $it" }
                                            }
                                        ))

                                        response
                                            .bodyAsChannel()
                                            .base64Chunks { chunk ->
                                                sendSerialized<ClientMessage>(ClientMessage.HttpBody(
                                                    requestId = message.requestId,
                                                    body = chunk
                                                ))
                                            }

                                        sendSerialized<ClientMessage>(ClientMessage.HttpEnd(
                                            requestId = message.requestId
                                        ))
                                    }
                                }
                                is ServerMessage.HttpBody -> {
                                    launch {
                                        val bodyBase64 = message.body
                                        val bodyBytes = Base64.decode(bodyBase64)
                                        requestBodies[message.requestId]?.writeFully(bodyBytes)
                                        requestBodies[message.requestId]?.flush()
                                    }
                                }
                                is ServerMessage.HttpEnd -> {
                                    launch {
                                        requestBodies[message.requestId]?.flushAndClose()
                                        requestBodies.remove(message.requestId)
                                    }
                                }
                                is ServerMessage.WsOpen -> {
                                    launch {
                                        val project = projectRepository.getById(message.project)
                                        if (project == null) {
                                            println(buildStyledString {
                                                red { +"Project ${message.project} not found in config" }
                                            })
                                            return@launch
                                        }

                                        val services = project.getWerkbankConfig().services
                                        val service: WerkbankConfig.Project.Service?
                                        if (message.service != null) {
                                            val requestedService = services.firstOrNull { service -> service.name == message.service }
                                            if (requestedService == null) {
                                                println(buildStyledString {
                                                    red { +"Service ${message.service} not found in project ${message.project}" }
                                                })
                                                return@launch
                                            }
                                            service = requestedService
                                        } else {
                                            val httpRules = project.getConfig().http
                                            val requestedService = httpRules.firstNotNullOfOrNull { rule ->
                                                if (rule.pathPrefixes.none { message.path.startsWith(it) }) return@firstNotNullOfOrNull null
                                                return@firstNotNullOfOrNull services.firstOrNull { it.name == rule.targetService }
                                            }
                                            if (requestedService == null) {
                                                println(buildStyledString {
                                                    red { +"No service found for path ${message.path} in project ${message.project}" }
                                                })
                                                return@launch
                                            }
                                            service = requestedService
                                        }

                                        val serviceState = service.serviceState
                                        val targetUrl: String
                                        when (serviceState) {
                                            WerkbankConfig.Project.Service.ServiceState.Disabled -> {
                                                println(buildStyledString {
                                                    red { +"Service ${service.name} is disabled in project ${message.project}" }
                                                })
                                                return@launch
                                            }
                                            WerkbankConfig.Project.Service.ServiceState.Local -> {
                                                val port = project.getConfig().services.first { it.name == service.name }.modes.local?.port
                                                if (port == null) {
                                                    println(buildStyledString {
                                                        red { +"Service ${service.name} has no local port" }
                                                    })
                                                    return@launch
                                                }
                                                targetUrl = "ws://localhost:$port${message.path}"
                                            }
                                            WerkbankConfig.Project.Service.ServiceState.Docker -> {
                                                val dockerConfig = project.getConfig().services.first { it.name == service.name }.modes.docker
                                                if (dockerConfig == null) {
                                                    println(buildStyledString {
                                                        red { +"Service ${service.name} has no docker configuration" }
                                                    })
                                                    return@launch
                                                }
                                                val container = project.getContainers().firstOrNull { it.name == dockerConfig.container }?.container
                                                if (container == null) {
                                                    println(buildStyledString {
                                                        red { +"Service ${service.name} has no docker container" }
                                                    })
                                                    return@launch
                                                }
                                                targetUrl = "ws://${dockerClient.containers.inspectContainer(container.getId()!!).networkSettings.networks.values.first().ipAddress}:${dockerConfig.port}${message.path}"
                                            }
                                        }

                                        println(buildStyledString {
                                            green { +"WebSocket: ${message.path}" }
                                            +" -> "
                                            gray { +targetUrl }
                                        })

                                        httpClient().webSocket(urlString = targetUrl) {
                                            wsProxyState[message.requestId] = this
                                            this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsOpened(
                                                requestId = message.requestId
                                            ))

                                            for (frame in incoming) {
                                                when (frame) {
                                                    is Frame.Text -> this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsText(
                                                        requestId = message.requestId,
                                                        text = frame.readText()
                                                    ))
                                                    is Frame.Binary -> this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsBinary(
                                                        requestId = message.requestId,
                                                        body = Base64.encode(frame.readBytes())
                                                    ))
                                                    is Frame.Close -> {
                                                        this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsClose(
                                                            requestId = message.requestId,
                                                            code = frame.readReason()?.code?.toInt() ?: 1000,
                                                            reason = frame.readReason()?.message ?: ""
                                                        ))
                                                        break
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        }

                                        wsProxyState.remove(message.requestId)
                                    }
                                }
                                is ServerMessage.WsText -> {
                                    launch {
                                        wsProxyState[message.requestId]?.send(Frame.Text(message.text))
                                    }
                                }
                                is ServerMessage.WsBinary -> {
                                    launch {
                                        wsProxyState[message.requestId]?.send(Frame.Binary(true, Base64.decode(message.body)))
                                    }
                                }
                                is ServerMessage.WsClose -> {
                                    launch {
                                        wsProxyState[message.requestId]?.close(CloseReason(message.code.toShort(), message.reason))
                                        wsProxyState.remove(message.requestId)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println(buildStyledString { red { +"Failed to connect to tunnel: ${e.stackTraceToString()}" } })
                delay(5.seconds)
            }
        }
    }
}