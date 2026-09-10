package app.werkbank.app.jobs.certificate_renew

import app.werkbank.app.jobs.PeriodicJob
import app.werkbank.database.Certificate
import app.werkbank.database.Certificates
import app.werkbank.database.DatabaseManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.minutes

class CertificateRenewJob: PeriodicJob(
    name = "certificate_renew",
    interval = 1.minutes
), KoinComponent {

    private val db by inject<DatabaseManager>()

    override suspend fun execute() {
        db.query {

        }
    }
}