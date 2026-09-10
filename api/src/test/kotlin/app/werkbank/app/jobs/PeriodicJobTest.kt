package app.werkbank.app.jobs

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class PeriodicJobTest {

    private class CountingJob(
        interval: Duration,
        initialDelay: Duration = Duration.ZERO,
        private val onTick: suspend (Int) -> Unit = {},
    ) : PeriodicJob("test", interval, initialDelay) {
        val ticks = AtomicInt(0)
        override suspend fun execute() = onTick(ticks.incrementAndFetch())
    }

    @Test
    fun `runs repeatedly until cancelled`() = runBlocking {
        val job = CountingJob(interval = 10.milliseconds)
        val running = launch { job.run() }

        withTimeout(5.seconds) { while (job.ticks.load() < 3) delay(5) }
        running.cancel()

        val ticksAtCancel = job.ticks.load()
        delay(50)
        assertTrue(job.ticks.load() == ticksAtCancel, "job kept ticking after cancellation")
    }

    @Test
    fun `keeps running after a failing tick`() = runBlocking {
        val job = CountingJob(interval = 10.milliseconds, onTick = { tick ->
            if (tick == 1) error("boom")
        })
        val running = launch { job.run() }

        withTimeout(5.seconds) { while (job.ticks.load() < 3) delay(5) }
        running.cancel()
    }

    @Test
    fun `waits for the initial delay before the first run`() = runBlocking {
        val job = CountingJob(interval = 10.milliseconds, initialDelay = 300.milliseconds)
        val running = launch { job.run() }

        delay(50)
        assertTrue(job.ticks.load() == 0, "job ran before its initial delay elapsed")

        withTimeout(5.seconds) { while (job.ticks.load() < 1) delay(5) }
        running.cancel()
    }
}
