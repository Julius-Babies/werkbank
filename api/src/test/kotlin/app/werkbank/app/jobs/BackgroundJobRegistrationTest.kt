package app.werkbank.app.jobs

import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `startBackgroundJobs` relies on Koin resolving every job through its [BackgroundJob] binding, so a
 * job is started by registering it and nothing else. Pins that contract.
 */
class BackgroundJobRegistrationTest {

    private class NoopJob(override val name: String) : BackgroundJob {
        override suspend fun run() = Unit
    }

    @Test
    fun `resolves every job bound to BackgroundJob`() {
        val koin = koinApplication {
            modules(module {
                single { NoopJob("a") } bind BackgroundJob::class
                single(org.koin.core.qualifier.named("b")) { NoopJob("b") } bind BackgroundJob::class
                single { "not a job" }
            })
        }.koin

        assertEquals(setOf("a", "b"), koin.getAll<BackgroundJob>().distinct().map { it.name }.toSet())
    }
}
