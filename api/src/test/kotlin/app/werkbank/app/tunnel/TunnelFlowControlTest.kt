package app.werkbank.app.tunnel

import app.werkbank.shared.tunnel.StreamCancelledException
import app.werkbank.shared.tunnel.TunnelFlowControl
import app.werkbank.shared.tunnel.TunnelFlowControl.Companion.CONNECTION_ID
import app.werkbank.shared.tunnel.TunnelFlowControl.Companion.CONNECTION_WINDOW
import app.werkbank.shared.tunnel.TunnelFlowControl.Companion.STREAM_WINDOW
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TunnelFlowControlTest {

    private val stream = Uuid.random()

    @Test
    fun `a stream pauses once its window is used up and resumes on credit`() = runBlocking<Unit> {
        val flow = TunnelFlowControl(enabled = true)
        flow.openStream(stream)
        flow.awaitSend(stream, STREAM_WINDOW.toInt())

        val blocked = async { flow.awaitSend(stream, 1) }
        assertNull(withTimeoutOrNull(100.milliseconds) { blocked.await() })

        flow.onCredit(stream, 1)
        withTimeout(1.seconds) { blocked.await() }
    }

    @Test
    fun `the connection window pauses every stream`() = runBlocking<Unit> {
        val flow = TunnelFlowControl(enabled = true)
        val other = Uuid.random()
        flow.openStream(stream)
        flow.openStream(other)
        flow.awaitSend(stream, CONNECTION_WINDOW.toInt())

        val blocked = async { flow.awaitSend(other, 1) }
        assertNull(withTimeoutOrNull(100.milliseconds) { blocked.await() })

        flow.onCredit(CONNECTION_ID, 1)
        withTimeout(1.seconds) { blocked.await() }
    }

    @Test
    fun `closing a stream releases its waiting sender`() = runBlocking<Unit> {
        val flow = TunnelFlowControl(enabled = true)
        flow.openStream(stream)
        flow.awaitSend(stream, STREAM_WINDOW.toInt())

        val blocked = async { runCatching { flow.awaitSend(stream, 1) } }
        yield()
        flow.closeStream(stream)
        assertTrue(withTimeout(1.seconds) { blocked.await() }.exceptionOrNull() is StreamCancelledException)
        assertFailsWith<StreamCancelledException> { flow.awaitSend(stream, 1) }
    }

    @Test
    fun `credit is returned in batches`() = runBlocking<Unit> {
        val flow = TunnelFlowControl(enabled = true)
        val sent = CopyOnWriteArrayList<Pair<Uuid, Long>>()
        val sender = launch { flow.sendCredits { id, bytes -> sent += id to bytes } }

        val quarter = (CONNECTION_WINDOW / 4).toInt()
        flow.onReceived(quarter - 1)
        flow.onReceived(1)
        val credit = flow.streamCredit(stream)
        credit.onConsumed((STREAM_WINDOW / 4).toInt())

        withTimeout(1.seconds) { while (sent.size < 2) yield() }
        assertEquals(listOf(CONNECTION_ID to quarter.toLong(), stream to STREAM_WINDOW / 4), sent.toList())
        flow.closeAll()
        sender.join()
    }

    @Test
    fun `disabled flow control never waits and returns no credit`() = runBlocking<Unit> {
        val flow = TunnelFlowControl(enabled = false)
        flow.awaitSend(stream, (CONNECTION_WINDOW * 4).toInt())
        flow.onReceived(CONNECTION_WINDOW.toInt())
        flow.streamCredit(stream).onConsumed(STREAM_WINDOW.toInt())
        flow.closeAll()
        val sent = mutableListOf<Long>()
        flow.sendCredits { _, bytes -> sent += bytes }
        assertEquals(emptyList(), sent)
    }
}
