package app.werkbank.app.certificates

import app.werkbank.APP_STORAGE_ROOT_QUALIFIER
import app.werkbank.config.AppConfig
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.uuid.Uuid

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
            // Generate root CA

            val keyFileResult = Command("openssl")
                .args("genrsa", "-out", rootCaKey.absolutePath, "4096")
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .spawn()
                .waitWithOutput()

            if (keyFileResult.status != 0) {
                println()
                throw RuntimeException(
                    """Failed to create root CA private key.
                |Status: ${keyFileResult.status}
                |Output: ${keyFileResult.stdout}
                |Error: ${keyFileResult.stderr}
                """.trimMargin()
                )
            }

            val cn = "Werkbank Cloud Self-signed Root CA"

            val tmpCsrRequestFile = File(System.getProperty("java.io.tmpdir"), "root-csr.csr")
            tmpCsrRequestFile.writeText(csrRequestConfigFileContent(cn))

            val certFileArgs = listOf("req", "-x509", "-new", "-nodes",
                "-key", rootCaKey.absolutePath,
                "-sha256",
                "-days", "1024",
                "-out", rootCa.absolutePath,
                "-config", tmpCsrRequestFile.absolutePath,
                "-extensions", "v3_req")

            val certFileResult = Command("openssl")
                .args(certFileArgs)
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .spawn()
                .waitWithOutput()

            if (certFileResult.status != 0) {
                println()
                throw RuntimeException(
                    """Failed to create root CA certificate.
                |Status: ${certFileResult.status}
                |Output: ${certFileResult.stdout}
                |Error: ${certFileResult.stderr}
                """.trimMargin()
                )
            }
        }
    }

    override suspend fun requestCertificate(domains: List<String>, targetCertFile: File, targetKeyFile: File) {
        val privateKeyResult = Command("openssl")
            .args(
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:4096",
                "-out",
                targetKeyFile.absolutePath
            )
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        if (privateKeyResult.status != 0) {
            throw RuntimeException(
                """Failed to create private key for ${domains.first()}.
                |Status: ${privateKeyResult.status}
                |Output: ${privateKeyResult.stdout}
                |Error: ${privateKeyResult.stderr}
                """.trimMargin()
            )
        }

        // Create certificate signing request
        val signingRequestFile = File(targetKeyFile.parent!!, "certificaterequest.${Uuid.random()}.csr")
        if (signingRequestFile.exists()) signingRequestFile.delete()

        val signingRequestResult = Command("openssl")
            .args(
                "req",
                "-new",
                "-key",
                targetKeyFile.absolutePath,
                "-out",
                signingRequestFile.absolutePath,
                "-subj",
                "/CN=${domains.first()}"
            )
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        if (signingRequestResult.status != 0) {
            throw RuntimeException(
                """Failed to create certificate signing request for ${domains.first()}.
                |Status: ${signingRequestResult.status}
                |Output: ${signingRequestResult.stdout}
                |Error: ${signingRequestResult.stderr}
                """.trimMargin()
            )
        }

        // Create SAN configuration
        val sanFile = File(targetKeyFile.parent!!, "san.${Uuid.random()}.conf")
        if (sanFile.exists()) sanFile.delete()
        sanFile.writeText(generateSanConfig(alternativeNames = domains))

        // Sign certificate
        val certificateResult = Command("openssl")
            .args(
                "x509", "-req",
                "-in", signingRequestFile.absolutePath,
                "-CA", rootCa.absolutePath,
                "-CAkey", rootCaKey.absolutePath,
                "-CAcreateserial",
                "-out", targetCertFile.absolutePath,
                "-days", "365",
                "-sha256",
                "-extfile", sanFile.absolutePath
            )
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        if (certificateResult.status != 0) {
            throw RuntimeException(
                """Failed to create certificate for ${domains.first()}.
                |Status: ${certificateResult.status}
                |Output: ${certificateResult.stdout}
                |Error: ${certificateResult.stderr}
                """.trimMargin()
            )
        }

        // copy to local certificate storage
        val dir = certificatesDir.resolve(domains.first().removePrefix("*.")).also { it.mkdirs() }
        targetCertFile.copyTo(dir.resolve("certificate.crt"), overwrite = true)
        targetKeyFile.copyTo(dir.resolve("private.key"), overwrite = true)
    }
}

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