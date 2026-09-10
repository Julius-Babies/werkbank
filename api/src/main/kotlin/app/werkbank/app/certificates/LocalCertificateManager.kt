package app.certificates

import app.werkbank.APP_STORAGE_ROOT_QUALIFIER
import app.werkbank.config.AppConfig
import app.werkbank.util.withFile
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import io.opentelemetry.kotlin.tracing.Span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.time.toKotlinInstant

class LocalCertificateManager: CertificateManager, KoinComponent {
    private val storageRoot by inject<File>(APP_STORAGE_ROOT_QUALIFIER)
    private val appConfig by inject<AppConfig>()
    private val certificatesDir by lazy { File(storageRoot, "certificates").also { it.mkdirs() } }
    private val rootCa = (appConfig.tls as? AppConfig.Tls.SelfSigned)?.rootCa?.certificatePath?.let(::File) ?: File(certificatesDir, "root-ca.crt")
    private val rootCaKey = (appConfig.tls as? AppConfig.Tls.SelfSigned)?.rootCa?.keyPath?.let(::File) ?: File(certificatesDir, "root-ca.key")

    override suspend fun init() {

        val result = withContext(Dispatchers.IO) {
            Command("which")
                .args("openssl")
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .spawn()
                .wait()
        }

        if (result != 0) {
            error("OpenSSL not found")
        }

        if (!rootCaKey.exists() || !rootCa.exists()) {
            // The root CA is long-lived, so both halves are written by openssl itself: `-out` creates
            // the key with 0600, which we would lose by piping it through here and writing it back.
            openssl(
                listOf("genrsa", "-out", rootCaKey.absolutePath, "4096"),
                failureMessage = "Failed to create root CA private key.",
            )

            withFile(
                content = csrRequestConfigFileContent("Werkbank Cloud Self-signed Root CA"),
                prefix = "werkbank-root-ca-csr",
                suffix = ".conf",
            ) { config ->
                openssl(
                    listOf(
                        "req", "-x509", "-new", "-nodes",
                        "-key", rootCaKey.absolutePath,
                        "-sha256",
                        "-days", "1024",
                        "-out", rootCa.absolutePath,
                        "-config", config.absolutePath,
                        "-extensions", "v3_req",
                    ),
                    failureMessage = "Failed to create root CA certificate.",
                )
            }
        }
    }

    /**
     * Issues a certificate for [domains], signed by the local root CA.
     *
     * The private key, the signing request and the certificate are piped between the openssl calls
     * instead of being written out: `-out` is omitted so openssl prints the PEM to stdout, and the
     * next call reads it back from stdin. Nothing but the (non-secret) SAN config touches the disk,
     * so a crash between two steps cannot leave a private key behind in the temp directory.
     */
    override suspend fun requestCertificate(
        span: Span,
        domains: List<String>
    ): CertificateResult {
        val commonName = domains.first()

        val privateKey = openssl(
            listOf("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:4096"),
            failureMessage = "Failed to create private key for $commonName.",
        )

        // `openssl req` only takes the key as a path, so hand it the read end of our pipe.
        val signingRequest = openssl(
            listOf("req", "-new", "-key", "/dev/stdin", "-subj", "/CN=$commonName"),
            stdin = privateKey,
            failureMessage = "Failed to create certificate signing request for $commonName.",
        )

        // `-extfile` has no stdin equivalent, and stdin already carries the signing request. The SAN
        // config is just the domain list, so a temp file is harmless as long as it is cleaned up.
        val certificate = withFile(
            content = generateSanConfig(alternativeNames = domains),
            prefix = "werkbank-san",
            suffix = ".conf",
        ) { sanFile ->
            openssl(
                listOf(
                    "x509", "-req",
                    "-CA", rootCa.absolutePath,
                    "-CAkey", rootCaKey.absolutePath,
                    "-CAcreateserial",
                    "-days", VALIDITY_DAYS.toString(),
                    "-sha256",
                    "-extfile", sanFile.absolutePath,
                ),
                stdin = signingRequest,
                failureMessage = "Failed to create certificate for $commonName.",
            )
        }

        span.addEvent("certificate.signed")

        return CertificateResult.Success(
            certificate = certificate.toByteArray(),
            privateKey = privateKey.toByteArray(),
            validUntil = certificate.parseNotAfter(),
        )
    }

    /**
     * Runs openssl with [args] and returns its stdout, feeding [stdin] in first when given.
     * A non-zero exit throws with [failureMessage] and openssl's stderr attached.
     */
    private suspend fun openssl(
        args: List<String>,
        stdin: String? = null,
        failureMessage: String,
    ): String = withContext(Dispatchers.IO) {
        val command = Command("openssl")
            .args(args)
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
        if (stdin != null) command.stdin(Stdio.Pipe)

        val child = command.spawn()
        if (stdin != null) {
            val writer = child.bufferedStdin() ?: error("openssl stdin unavailable")
            // Inputs are a few kB of PEM, far below the pipe buffer, so this cannot block on a child
            // that is not draining yet. waitWithOutput() closes stdin afterwards, giving it its EOF.
            writer.writeLine(stdin.trimEnd())
            writer.flush()
        }

        val output = child.waitWithOutput()
        if (output.status != 0) {
            throw RuntimeException(
                """$failureMessage
                |Status: ${output.status}
                |Error: ${output.stderr}
                """.trimMargin()
            )
        }
        output.stdout.orEmpty()
    }

    companion object {
        private const val VALIDITY_DAYS = 365
    }
}

/** Reads the expiry back out of the signed certificate rather than recomputing the validity window. */
private fun String.parseNotAfter() = (CertificateFactory.getInstance("X.509")
    .generateCertificate(ByteArrayInputStream(toByteArray())) as X509Certificate)
    .notAfter.toInstant().toKotlinInstant()

private fun csrRequestConfigFileContent(cn: String) = """
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = $cn
C = DE
ST = Saxony
L = Dresden
O = Werkbank

[v3_req]
basicConstraints = critical,CA:TRUE
"""

private fun generateSanConfig(alternativeNames: List<String>) = buildString {
    appendLine("authorityKeyIdentifier=keyid,issuer")
    appendLine("basicConstraints=CA:FALSE")
    appendLine("keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment")
    appendLine("subjectAltName = @alt_names")
    appendLine("")
    appendLine("[alt_names]")
    alternativeNames.forEachIndexed { index, name ->
        appendLine("DNS.${index + 1} = $name")
    }
}
