package app.werkbank.app.jobs

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PeriodicJobTest {

    private class CountingJob(
        interval: kotlin.time.Duration,
        initialDelay: kotlin.time.Duration = kotlin.time.Duration.ZERO,
        private val onTick: suspend (Int) -> Unit = {},
    ) : PeriodicJob("test", interval, initialDelay) {
        val ticks = AtomicInteger(0)
        override suspend fun execute() = onTick(ticks.incrementAndGet())
    }

    @Test
    fun `runs repeatedly until cancelled`() = runBlocking {
        val job = CountingJob(interval = 10.milliseconds)
        val running = launch { job.run() }

        withTimeout(5.seconds) { while (job.ticks.get() < 3) delay(5) }
        running.cancel()

        val ticksAtCancel = job.ticks.get()
        delay(50)
        assertTrue(job.ticks.get() == ticksAtCancel, "job kept ticking after cancellation")
    }

    @Test
    fun `keeps running after a failing tick`() = runBlocking {
        val job = CountingJob(interval = 10.milliseconds, onTick = { tick ->
            if (tick == 1) error("boom")
        })
        val running = launch { job.run() }

        withTimeout(5.seconds) { while (job.ticks.get() < 3) delay(5) }
        running.cancel()
    }

    @Test
    fun `waits for the initial delay before the first run`() = runBlocking {
        val job = CountingJob(interval = 10.milliseconds, initialDelay = 300.milliseconds)
        val running = launch { job.run() }

        delay(50)
        assertTrue(job.ticks.get() == 0, "job ran before its initial delay elapsed")

        withTimeout(5.seconds) { while (job.ticks.get() < 1) delay(5) }
        running.cancel()
    }
}
