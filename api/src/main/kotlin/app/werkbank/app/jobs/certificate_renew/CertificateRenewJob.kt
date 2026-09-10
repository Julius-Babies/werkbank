package app.werkbank.app.jobs.certificate_renew

import app.werkbank.app.jobs.PeriodicJob
import app.werkbank.app.queue.certificate.CertificateQueue
import app.werkbank.config.AppConfig
import app.werkbank.database.Certificates
import app.werkbank.database.DatabaseManager
import app.werkbank.database.User
import app.werkbank.database.Users
import io.opentelemetry.kotlin.tracing.Span
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Queues a new certificate for every user whose certificates are expired or about to expire.
 * Starts [RENEW_BEFORE_EXPIRY] ahead of expiry so a slow ACME order still has room to succeed.
 */
class CertificateRenewJob : PeriodicJob(
    name = "certificate-renew",
    interval = 6.hours,
), KoinComponent {

    private val db by inject<DatabaseManager>()
    private val queue by inject<CertificateQueue>()
    private val appConfig by inject<AppConfig>()
    private val logger = LoggerFactory.getLogger(CertificateRenewJob::class.java)

    override suspend fun execute(span: Span) {
        val deadline = Clock.System.now() + RENEW_BEFORE_EXPIRY

        // Anti-join instead of "newest certificate per user, then filter": it only matters whether
        // *any* certificate is still valid, so there is nothing to aggregate. Expired rows fail the
        // inner predicate and users without any certificate fall out for free.
        val users = db.query {
            User.find {
                Users.id notInSubQuery Certificates
                    .select(Certificates.user)
                    .where { Certificates.validUntil greater deadline }
            }.toList()
        }

        span.setLongAttribute("certificate.renewals", users.size.toLong())
        if (users.isEmpty()) return
        logger.info("Renewing certificates for {} user(s)", users.size)

        users.forEach { user ->
            queue.submit(
                CertificateQueue.Request(
                    domains = listOf("*.${user.username.lowercase()}.${appConfig.domainSuffix}"),
                    createdAt = Clock.System.now(),
                    targetUser = user,
                )
            )
        }
    }

    companion object {
        private val RENEW_BEFORE_EXPIRY = 7.days
    }
}
