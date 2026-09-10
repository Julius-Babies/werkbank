package app.werkbank.app.jobs

import app.werkbank.util.launchConnectionJob
import io.ktor.server.application.Application
import org.koin.ktor.ext.getKoin

/**
 * A long-lived unit of background work, started once at application startup and cancelled with it.
 *
 * Use [PeriodicJob] for recurring work and [QueueProcessorJob] to drain a [JobQueue]. Both contain
 * per-iteration failures, so one bad tick or one bad item never ends the job.
 *
 * Register implementations in Koin with `bind BackgroundJob::class`; [startBackgroundJobs] resolves
 * and starts every binding, so adding a job needs no change to the startup code.
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
 * Jobs are launched via [launchConnectionJob] so that a job which dies despite its internal handling
 * cannot cancel the Application — that would close the Koin root scope and fail every subsequent
 * request with `ClosedScopeException`.
 */
fun Application.startBackgroundJobs() {
    val jobs = getKoin().getAll<BackgroundJob>().distinct()
    environment.log.info("Starting {} background job(s): {}", jobs.size, jobs.joinToString { it.name })
    jobs.forEach { job ->
        launchConnectionJob(this, "job:${job.name}") { job.run() }
    }
}
