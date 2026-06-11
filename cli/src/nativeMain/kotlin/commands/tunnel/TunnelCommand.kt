package commands.tunnel

import app.config.MainConfig
import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.ServerMessage
import app.werkbank.shared.tunnel.base64Chunks
import app.werkbank.shared.tunnel.json
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import http.httpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.io.encoding.Base64
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TunnelCommand : SuspendingCliktCommand("tunnel"), KoinComponent {

    private val mainConfig by inject<MainConfig>()
    val tunnelRequestResolver = TunnelRequestResolver()

    override suspend fun run() {
        coroutineScope {
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

                                            val target = tunnelRequestResolver.getTarget(
                                                projectKey = message.project,
                                                serviceKey = message.service,
                                                path = message.path,
                                                isWebsocket = false,
                                            ) ?: return@launch

                                            printRequest(
                                                method = message.method,
                                                projectKey = target.project.id,
                                                serviceKey = target.service.name,
                                                path = message.path,
                                                targetUrl = target.url,
                                            )

                                            val request = httpClient().prepareRequest(
                                                urlString = target.url,
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
                                            val target = tunnelRequestResolver.getTarget(
                                                projectKey = message.project,
                                                serviceKey = message.service,
                                                path = message.path,
                                                isWebsocket = true,
                                            ) ?: return@launch

                                            printRequest(
                                                method = "WEBSOCKET",
                                                projectKey = target.project.id,
                                                serviceKey = target.service.name,
                                                path = message.path,
                                                targetUrl = target.url,
                                            )

                                            httpClient().webSocket(urlString = target.url) {
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

    companion object {

        private val mutex = Mutex()

        context(scope: CoroutineScope)
        fun printRequest(
            method: String,
            projectKey: String,
            serviceKey: String,
            path: String,
            targetUrl: String
        ) {
            scope.launch {
                mutex.withLock {
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
                        fun String.methodPadding() = " ${this.uppercase().padEnd(9, ' ')} "
                        when (val method = method.uppercase()) {
                            "GET" -> bgGreen { black { +method.methodPadding() } }
                            "POST" -> bgYellow { black { +method.methodPadding() } }
                            "PUT" -> bgBlue { white { +method.methodPadding() } }
                            "PATCH" -> bgPurple { white { +method.methodPadding() } }
                            "DELETE" -> bgRed { white { +method.methodPadding() } }
                            "HEAD" -> bgGray { white { +method.methodPadding() } }
                            "OPTIONS" -> bgGray { white { +method.methodPadding() } }
                            "WEBSOCKET" -> bgCyan { black { +method.methodPadding() } }
                            else -> +method.methodPadding()
                        }
                        +" "
                        bold {
                            +projectKey
                            +"."
                            italic { +serviceKey }
                        }
                        +" / "
                        +path.removePrefix("/")
                        +"->"
                        +" "
                        gray { +targetUrl }
                    })
                }
            }
        }
    }
}