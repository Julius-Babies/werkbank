package app.werkbank.app.tunnel

import app.werkbank.shared.tunnel.ClientMessage
import app.werkbank.shared.tunnel.StreamCancelledException
import app.werkbank.shared.tunnel.json
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.toByteArray
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ProxyRequestReleaseTest {

    private fun record(requestId: Uuid) = TunnelRequestRecord(
        requestId = requestId,
        kind = RequestKind.HTTP,
        method = "GET",
        uri = "/",
        projectId = "project",
        projectName = "project",
        serviceName = null,
        requestHeaders = emptyMap(),
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

    /**
     * Runs [scenario] on the server side of a real tunnel socket with flow control on, standing in for
     * the proxied call with its own job. Returns the text frames the tunnel host received, up to the
     * http.cancel if [awaitCancel].
     */
    private fun onTunnel(
        awaitCancel: Boolean,
        scenario: suspend (TunnelInstance, CoroutineScope) -> Unit,
    ): List<String> {
        val received = CopyOnWriteArrayList<String>()
        var failure: Throwable? = null
        testApplication {
            install(WebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(json) }
            routing {
                webSocket("/tunnel") {
                    val tunnel = TunnelInstance(this, flowControl = true)
                    tunnel.announceFlowControl()
                    val call = CoroutineScope(Job())
                    try {
                        withTimeout(5.seconds) {
                            scenario(tunnel, call)
                            // Nothing launched for the request may outlive it.
                            call.coroutineContext[Job]!!.children.toList().joinAll()
                        }
                    } catch (e: Throwable) {
                        failure = e
                        return@webSocket
                    }
                    // The http.cancel is sent in the background; the host hangs up once it has it.
                    if (awaitCancel) for (frame in incoming) {}
                }
            }
            createClient { install(ClientWebSockets) }.webSocket("/tunnel") {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    received += text
                    if (awaitCancel && "http.cancel" in text) break
                }
            }
        }
        failure?.let { throw it }
        return received
    }

    @Test
    fun `a request still streaming when the call ends is cancelled`() {
        val requestId = Uuid.random()
        lateinit var request: ProxyRequest
        val received = onTunnel(awaitCancel = true) { tunnel, call ->
            request = tunnel.startRequest(record(requestId), call)
            request.send(null)
            tunnel.dispatch(ClientMessage.HttpResponse(requestId, 200, emptyList()))
            request.awaitResponse()
            tunnel.dispatchBinary(requestId, ByteArray(1024))

            request.release()

            assertFailsWith<StreamCancelledException> { tunnel.flow.awaitSend(requestId, 1) }
        }

        val snapshot = request.snapshot.value
        assertNotNull(snapshot.error)
        assertNotNull(snapshot.completedAt)
        assertTrue(received.any { "http.cancel" in it })
    }

    @Test
    fun `a request whose response was read to its end stays successful`() {
        val requestId = Uuid.random()
        lateinit var request: ProxyRequest
        onTunnel(awaitCancel = false) { tunnel, call ->
            request = tunnel.startRequest(record(requestId), call)
            request.send(null)
            tunnel.dispatch(ClientMessage.HttpResponse(requestId, 200, emptyList()))
            tunnel.dispatchBinary(requestId, ByteArray(1024))
            tunnel.dispatch(ClientMessage.HttpEnd(requestId))
            val body = request.awaitResponse().body!!
            assertEquals(1024, body.toByteArray().size)

            // Right after EOF, possibly before the request's own finish ran.
            request.release()

            assertFailsWith<StreamCancelledException> { tunnel.flow.awaitSend(requestId, 1) }
        }

        val snapshot = request.snapshot.value
        assertNull(snapshot.error)
        assertNotNull(snapshot.completedAt)
    }
}
