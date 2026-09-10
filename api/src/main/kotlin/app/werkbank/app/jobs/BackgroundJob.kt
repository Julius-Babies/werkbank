package app.werkbank.app.jobs

import app.werkbank.util.launchConnectionJob
import io.ktor.server.application.Application
import org.koin.ktor.ext.getKoin

/**
 * Long-lived background work, started at application startup and cancelled with it.
 *
 * Implement [PeriodicJob] for recurring work or [QueueProcessorJob] to drain a [JobQueue]; both
 * contain per-iteration failures. Register with `bind BackgroundJob::class` to get started.
 */
interface BackgroundJob {

    /** Identifies the job in logs. */
    val name: String

    /** Runs until the surrounding scope is cancelled. */
    suspend fun run()
}

/**
 * Starts every [BackgroundJob] bound in Koin on the application scope.
 *
 * Uses [launchConnectionJob] so a dying job cannot cancel the Application, which would close the
 * Koin root scope and fail every later request with `ClosedScopeException`.
 */
fun Application.startBackgroundJobs() {
    val jobs = getKoin().getAll<BackgroundJob>().distinct()
    environment.log.info("Starting {} background job(s): {}", jobs.size, jobs.joinToString { it.name })
    jobs.forEach { job ->
        launchConnectionJob(this, "job:${job.name}") { job.run() }
    }
}
