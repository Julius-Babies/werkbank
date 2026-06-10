package commands.tunnel

import app.config.MainConfig
import app.repository.ProjectRepository
import app.werkbank.shared.tunnel.ServerMessage
import app.werkbank.shared.tunnel.json
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import http.httpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.io.encoding.Base64
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TunnelCommand : SuspendingCliktCommand("tunnel"), KoinComponent {

    private val mainConfig by inject<MainConfig>()
    private val projectRepository by inject<ProjectRepository>()

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

        while (true) {
            try {
                httpClient().webSocket(
                    urlString = "wss://werkbank.werkbank.space/api/tunnel",
                    request = {
                        bearerAuth(authToken)
                    }
                ) {
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
                                        val service = services.firstOrNull { service -> service.name == message.service }
                                        if (service == null) {
                                            println(buildStyledString {
                                                red { +"Service ${message.service} not found in project ${message.project}" }
                                            })
                                            return@launch
                                        }

                                        val targetUrl = "https://localhost:443${message.path}"

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
                                        response.bodyAsText().let(::println)
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