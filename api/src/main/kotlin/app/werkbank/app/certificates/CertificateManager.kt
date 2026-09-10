package app.certificates

import io.opentelemetry.kotlin.tracing.Span
import kotlin.time.Instant

interface CertificateManager {
    suspend fun init()
    suspend fun requestCertificate(
        span: Span,
        domains: List<String>,
    ): CertificateResult
}

sealed class CertificateResult {
    data class Error(val message: String) : CertificateResult()
    class Success(
        val certificate: ByteArray,
        val privateKey: ByteArray,
        val validUntil: Instant,
    ) : CertificateResult()
}