package app.werkbank.app.certificates

import java.io.File

interface CertificateManager {
    suspend fun init()
    suspend fun requestCertificate(
        domains: List<String>,
        targetCertFile: File,
        targetKeyFile: File,
    )
}