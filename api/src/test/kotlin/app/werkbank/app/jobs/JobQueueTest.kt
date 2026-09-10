package app.werkbank.app.jobs

import io.opentelemetry.kotlin.tracing.Span
import io.opentelemetry.kotlin.tracing.SpanKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class JobQueueTest {

    private class CollectingJob(
        queue: JobQueue<String>,
        workers: Int = 1,
        private val handle: suspend (String) -> Unit = {},
    ) : QueueProcessorJob<String>("test", queue, workers) {
        val processed = CopyOnWriteArrayList<String>()
        override suspend fun process(item: String, span: Span) {
            handle(item)
            processed += item
        }
    }

    @Test
    fun `processes submitted items`() = jobTest {
        val queue = JobQueue<String>("test")
        val job = CollectingJob(queue)
        val running = launch { job.run() }

        assertTrue(queue.submit("a"))
        assertTrue(queue.submit("b"))

        withTimeout(5.seconds) { while (job.processed.size < 2) delay(5) }
        assertEquals(listOf("a", "b"), job.processed.toList())
        running.cancel()
    }

    @Test
    fun `drops duplicates while a key is in flight and accepts it again afterwards`() = jobTest {
        val release = CompletableDeferred<Unit>()
        val queue = JobQueue<String>("test", deduplicateBy = { it.substringBefore(':') })
        val job = CollectingJob(queue) { release.await() }
        val running = launch { job.run() }

        assertTrue(queue.submit("user:1"))
        assertFalse(queue.submit("user:2"), "duplicate key was queued")
        assertTrue(queue.submit("other:1"), "a different key was rejected")

        release.complete(Unit)
        withTimeout(5.seconds) { while (job.processed.size < 2) delay(5) }
        assertEquals(setOf("user:1", "other:1"), job.processed.toSet())
        assertEquals(1, queue.dropped)

        // The key is free again once processing finished.
        assertTrue(queue.submit("user:3"))
        withTimeout(5.seconds) { while (job.processed.size < 3) delay(5) }
        running.cancel()
    }

    @Test
    fun `drops and reports items once the queue is full`() = jobTest {
        val dropped = CopyOnWriteArrayList<String>()
        val queue = JobQueue<String>("test", capacity = 2, onDrop = { dropped += it })

        assertTrue(queue.submit("a"))
        assertTrue(queue.submit("b"))
        assertFalse(queue.submit("c"), "item was accepted beyond the queue capacity")

        assertEquals(listOf("c"), dropped.toList())
        assertEquals(1, queue.dropped)
    }

    @Test
    fun `emits a consumer span per item carrying how long it waited`() = jobTest { exporter ->
        val queue = JobQueue<String>("test")
        val job = CollectingJob(queue) { delay(30) }
        val running = launch { job.run() }

        queue.submit("a")
        queue.submit("b")

        withTimeout(5.seconds) { while (exporter.exportedSpans.size < 2) delay(5) }
        running.cancel()

        val spans = exporter.exportedSpans
        assertEquals("job test process", spans.first().name)
        assertEquals(SpanKind.CONSUMER, spans.first().spanKind)
        assertEquals("test", spans.first().attributes[JOB_NAME])

        // "b" waited behind "a", so its recorded queue time must be the larger one.
        val waits = spans.map { assertNotNull(it.attributes[JOB_QUEUE_WAIT_MS] as? Long) }
        assertTrue(waits[1] > waits[0], "queue wait time did not grow behind a busy worker: $waits")
    }
}
