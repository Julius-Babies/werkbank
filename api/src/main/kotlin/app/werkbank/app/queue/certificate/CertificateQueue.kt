package app.werkbank.app.queue.certificate

import app.werkbank.app.jobs.JobQueue
import app.werkbank.database.User
import kotlin.time.Instant

/**
 * Certificate requests waiting to be issued by [CertificateProcessorJob]. Deduplicated per user and
 * domain set, so repeated logins or renewal ticks do not queue the same ACME order twice.
 */
class CertificateQueue : JobQueue<CertificateQueue.Request>(
    name = "certificate",
    deduplicateBy = { it.targetUser.id.value to it.domains },
) {
    data class Request(
        val domains: List<String>,
        val createdAt: Instant,
        val targetUser: User,
    )
}
