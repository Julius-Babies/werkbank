package commands.tunnel

import app.config.MainConfig
import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.ServerMessage
import app.werkbank.shared.tunnel.json
import app.werkbank.shared.tunnel.rawChunks
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import http.httpClient
import http.isServerNotRunningException
import http.isTimeoutException
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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
    private val client = httpClient()

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
                    client.webSocket(
                        urlString = "wss://${mainConfig.getConfig().werkbankCloudDomain}/api/tunnel",
                        request = {
                            bearerAuth(authToken)
                        }
                    ) serverSession@{

                        println(buildStyledString {
                            green { +"Connected to your werkbank cloud tunnel" }
                            +" (${mainConfig.getConfig().werkbankCloudDomain})"
                        })

                        for (message in incoming) {
                            when (message) {
                                is Frame.Binary -> {
                                    val bytes = message.readBytes()
                                    if (bytes.size < 16) continue
                                    val id = Uuid.fromByteArray(bytes.copyOfRange(0, 16))
                                    requestBodies[id]?.writeFully(bytes.copyOfRange(16, bytes.size))
                                    requestBodies[id]?.flush()
                                }
                                is Frame.Text -> {
                                    when (val msg = json.decodeFromString<ServerMessage>(message.readText())) {
                                        is ServerMessage.HttpRequest -> {
                                            if (msg.method != "GET") {
                                                requestBodies[msg.requestId] = ByteChannel(autoFlush = true)
                                            }

                                            launch {
                                                val channel = requestBodies[msg.requestId]
                                                val target = tunnelRequestResolver.getTarget(
                                                    projectKey = msg.project,
                                                    serviceKey = msg.service,
                                                    path = msg.path,
                                                    isWebsocket = false,
                                                ) ?: return@launch

                                                printRequest(
                                                    method = msg.method,
                                                    projectKey = target.project.id,
                                                    serviceKey = target.service.name,
                                                    path = msg.path,
                                                    targetUrl = target.url,
                                                )

                                                val response = try {
                                                    client.prepareRequest(
                                                        urlString = target.url,
                                                    ) {
                                                        method = HttpMethod(msg.method)
                                                        msg.headers.forEach { header ->
                                                            val (key, value) = header.split(": ", limit = 2)
                                                            headers.append(key, value)
                                                        }
                                                        if (channel != null) setBody(channel)
                                                    }.execute()
                                                } catch (e: Exception) {
                                                    if (e.isTimeoutException()) {
                                                        sendSerialized<ClientMessage>(ClientMessage.Timeout(requestId = msg.requestId))
                                                        return@launch
                                                    }

                                                    if (e.isServerNotRunningException()) {
                                                        sendSerialized<ClientMessage>(ClientMessage.ServerNotRuning(msg.requestId))
                                                        return@launch
                                                    }
                                                    println(buildStyledString { red { +"Failed to connect to tunnel: ${e.stackTraceToString()}" } })
                                                    return@launch
                                                }

                                                sendSerialized<ClientMessage>(ClientMessage.HttpResponse(
                                                    requestId = msg.requestId,
                                                    statusCode = response.status.value,
                                                    headers = response.headers.entries().flatMap { (key, values) ->
                                                        values.map { "$key: $it" }
                                                    }
                                                ))

                                                response.bodyAsChannel().rawChunks { rawBytes ->
                                                    val frameData = ByteArray(16 + rawBytes.size)
                                                    msg.requestId.toByteArray().copyInto(frameData)
                                                    rawBytes.copyInto(frameData, 16)
                                                    this@serverSession.send(Frame.Binary(true, frameData))
                                                }

                                                sendSerialized<ClientMessage>(ClientMessage.HttpEnd(
                                                    requestId = msg.requestId
                                                ))
                                            }
                                        }
                                        is ServerMessage.HttpBody -> {
                                            val bodyBytes = Base64.decode(msg.body)
                                            requestBodies[msg.requestId]?.writeFully(bodyBytes)
                                            requestBodies[msg.requestId]?.flush()
                                        }
                                        is ServerMessage.HttpEnd -> {
                                            requestBodies[msg.requestId]?.flushAndClose()
                                            requestBodies.remove(msg.requestId)
                                        }
                                        is ServerMessage.WsOpen -> {
                                            launch {
                                                val target = tunnelRequestResolver.getTarget(
                                                    projectKey = msg.project,
                                                    serviceKey = msg.service,
                                                    path = msg.path,
                                                    isWebsocket = true,
                                                ) ?: return@launch

                                                printRequest(
                                                    method = "WEBSOCKET",
                                                    projectKey = target.project.id,
                                                    serviceKey = target.service.name,
                                                    path = msg.path,
                                                    targetUrl = target.url,
                                                )

                                                client.webSocket(urlString = target.url) {
                                                    wsProxyState[msg.requestId] = this
                                                    this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsOpened(
                                                        requestId = msg.requestId
                                                    ))

                                                    for (frame in incoming) {
                                                        when (frame) {
                                                            is Frame.Text -> this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsText(
                                                                requestId = msg.requestId,
                                                                text = frame.readText()
                                                            ))
                                                            is Frame.Binary -> this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsBinary(
                                                                requestId = msg.requestId,
                                                                body = Base64.encode(frame.readBytes())
                                                            ))
                                                            is Frame.Close -> {
                                                                this@serverSession.sendSerialized<ClientMessage>(ClientMessage.WsClose(
                                                                    requestId = msg.requestId,
                                                                    code = frame.readReason()?.code?.toInt() ?: 1000,
                                                                    reason = frame.readReason()?.message ?: ""
                                                                ))
                                                                break
                                                            }
                                                            else -> {}
                                                        }
                                                    }
                                                }

                                                wsProxyState.remove(msg.requestId)
                                            }
                                        }
                                        is ServerMessage.WsText -> {
                                            wsProxyState[msg.requestId]?.send(Frame.Text(msg.text))
                                        }
                                        is ServerMessage.WsBinary -> {
                                            wsProxyState[msg.requestId]?.send(Frame.Binary(true, Base64.decode(msg.body)))
                                        }
                                        is ServerMessage.WsClose -> {
                                            wsProxyState[msg.requestId]?.close(CloseReason(msg.code.toShort(), msg.reason))
                                            wsProxyState.remove(msg.requestId)
                                        }
                                    }
                                }
                                else -> {}
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
