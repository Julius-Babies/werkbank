package app.werkbank.app.cli.download_certificate

import app.werkbank.database.Certificate
import app.werkbank.database.Certificates
import app.werkbank.database.DatabaseManager
import app.werkbank.plugins.auth.AUTH_USER_JWT
import app.werkbank.plugins.auth.UserPrincipal
import app.werkbank.shared.download_certificate.DownloadResponse
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.ktor.ext.inject
import kotlin.io.encoding.Base64

fun Route.downloadCertificates() {
    val db by inject<DatabaseManager>()
    authenticate(AUTH_USER_JWT) {
        get {
            val user = call.principal<UserPrincipal>()!!

            db.query {
                val certificate = Certificates
                    .selectAll()
                    .where { Certificates.user eq user.user.id.value }
                    .orderBy(Certificates.createdAt, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
                    ?.let(Certificate::wrapRow)

                if (certificate == null) {
                    return@query call.respond<DownloadResponse>(message = DownloadResponse.NotFound)
                }

                return@query call.respond<DownloadResponse>(
                    message = DownloadResponse.Success(
                        certificate = Base64.encode(certificate.certificate.bytes),
                        privateKey = Base64.encode(certificate.privateKey.bytes),
                    )
                )
            }
        }
    }
}