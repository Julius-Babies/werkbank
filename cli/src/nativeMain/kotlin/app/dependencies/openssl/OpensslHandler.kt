package app.dependencies.openssl

import app.SudoManager
import app.config.DEFAULT_BASE_DOMAIN
import app.config.WERKBANK_BASE_DOMAIN
import app.dependencies.AppDependency
import app.repository.ProjectRepository
import app.storage.isDevMode
import app.storage.storageRoot
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import es.jvbabi.kfile.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.CHECK
import util.REPLACE_LINE
import util.buildStyledString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class OpensslHandler : KoinComponent {
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))
    private val projectRepository by inject<ProjectRepository>()
    val isOpensslAvailable = CompletableDeferred<Boolean>()
    val internalCertificateDirectory = storageRoot
        .resolve("certificates")
        .resolve("internal")

    val externalCertificateDirectory = storageRoot
        .resolve("certificates")
        .resolve("external")

    suspend fun initialize() {
        val result = withContext(Dispatchers.IO) {
            Command("which")
                .args("openssl")
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .spawn()
                .wait()
        }
        if (result != 0) {
            isOpensslAvailable.complete(false)
            return
        }

        if (!isRootCaSetUp()) createRootCa()
        createInternalCertificates()

        isOpensslAvailable.complete(true)
    }

    val certificatesFolder = storageRoot.resolve("certificates").apply {
        if (!exists()) mkdir()
    }

    val rootCaFile = certificatesFolder.resolve("rootCA.crt")
    val rootKeyFile = certificatesFolder.resolve("rootCA.key")
    val keyStoreFile = certificatesFolder.resolve("keystore.jks")
    val keyStorePassword = "changeit"

    /** Base domains the root CA is created for, written as subject alternative names. */
    val rootCaDomains get() = listOf(DEFAULT_BASE_DOMAIN, WERKBANK_BASE_DOMAIN)

    fun isRootCaSetUp(): Boolean {
        if (!rootCaFile.exists()) return false
        if (!rootKeyFile.exists()) return false
        if (!keyStoreFile.exists()) return false
        return true
    }

    suspend fun createRootCa() {
        println(buildStyledString {
            cyan { +"Creating root CA certificate" }
        })
        println()

        // Step 1: Generate private key
        println(buildStyledString {
            blue { +"Step 1" }
            +": Generating private key (4096-bit RSA)"
        })
        print(buildStyledString {
            +"   $ "
            gray { +"openssl genrsa -out ${rootKeyFile.absolutePath} 4096" }
        })

        val keyFileResult = Command("openssl")
            .args("genrsa", "-out", rootKeyFile.absolutePath, "4096")
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

        println(buildStyledString {
            +REPLACE_LINE
            +"   "
            green { +"$CHECK Private key generated" }
        })
        println()

        // Step 2: Create certificate
        println(buildStyledString {
            blue { +"Step 2" }
            +": Creating self-signed root certificate"
        })

        val cn = if (isDevMode) "Werkbank Dev Root CA" else "Werkbank Root CA"

        val tmpCsrRequestFile = File.getTempDirectory().resolve("root-csr.csr")
        tmpCsrRequestFile.writeText(csrRequestConfigFileContent(cn, rootCaDomains))

        val certFileArgs = listOf("req", "-x509", "-new", "-nodes",
            "-key", rootKeyFile.absolutePath,
            "-sha256",
            "-days", "1024",
            "-out", rootCaFile.absolutePath,
            "-config", tmpCsrRequestFile.absolutePath,
            "-extensions", "v3_req")

        print(buildStyledString {
            +"   $ "
            gray { +"openssl ${certFileArgs.joinToString(" ")}" }
        })

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

        println(buildStyledString {
            +REPLACE_LINE
            +"   "
            green { +"$CHECK Certificate created (valid for 1024 days)" }
        })

        // Step 3: Create keystore
        println(buildStyledString {
            blue { +"Step 3" }
            +": Creating keystore"
        })
        val createKeystoreArgs = listOf(
            "pkcs12",
            "-export",
            "-in",
            rootCaFile.absolutePath,
            "-inkey",
            rootKeyFile.absolutePath,
            "-out",
            keyStoreFile.absolutePath,
            "-name", cn,
            "-password", "pass:$keyStorePassword"
        )
        print(buildStyledString {
            +"   $ "
            gray { +"openssl ${createKeystoreArgs.joinToString(" ")}" }
        })

        val createKeystoreResult = Command("openssl")
            .args(createKeystoreArgs)
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        if (createKeystoreResult.status != 0) {
            println()
            throw RuntimeException(
                """Failed to create Keystore file.
                |Status: ${certFileResult.status}
                |Output: ${certFileResult.stdout}
                |Error: ${certFileResult.stderr}
                """.trimMargin()
            )
        }

        println(buildStyledString {
            +REPLACE_LINE
            +"   "
            green { +"$CHECK Keystore created" }
        })

        println()

        println(buildStyledString {
            green { +"$CHECK Root CA created successfully" }
        })

        println(buildStyledString {
            yellow { +"   Certificate location: " }
            bold { +rootCaFile.absolutePath }
            +"\n"
            yellow { +"   Key location: " }
            bold { +rootKeyFile.absolutePath }
            +"\n"
            yellow { +"   Keystore location: " }
            bold { +keyStoreFile.absolutePath }
            aqua { +" (Password: $keyStorePassword)" }
        })
        println()

        println(buildStyledString { cyan { +"Deleting old leaf certificates if present" } })
        projectRepository.getAllProjects().forEach { project ->
            if (project.certificateFile.exists()) project.certificateFile.delete()
            if (project.privateKeyFile.exists()) project.privateKeyFile.delete()
        }

        // Installation prompt
        println(buildStyledString {
            yellow { +"Do you want to install the root CA in your system now? (y/n)" }
        })
        val response = readln()

        if (response.lowercase() == "y") {
            println()
            println(buildStyledString {
                cyan { +"Installing root CA..." }
            })

            val installedCAs = getInstalledRootCAs(SudoManager())
            val existingCa = installedCAs.firstOrNull { it.name == cn }

            if (existingCa != null) {
                println(buildStyledString {
                    yellow { +"   Found existing CA installation: " }
                    +cn
                })
                println(buildStyledString {
                    yellow { +"   Removing old version (fingerprint: ${existingCa.fingerprint})" }
                })
                uninstallRootCa(existingCa.fingerprint, SudoManager())
            }

            installRootCa(rootCaFile, SudoManager())

            println(buildStyledString {
                green { +"$CHECK Root CA installed successfully" }
            })
        } else {
            println(buildStyledString {
                gray { +"   You can install the certificate later from:" }
            })
            println(buildStyledString {
                gray { +"   " }
                +rootCaFile.absolutePath
            })
        }
        println()
    }

    fun createInternalCertificates() {
        if (!internalCertificateDirectory.exists()) internalCertificateDirectory.mkdir(recursive = true)
        if (!externalCertificateDirectory.exists()) externalCertificateDirectory.mkdir(recursive = true)

        dependencies
            .filter { it.webfacingDomains.isNotEmpty() }
            .forEach { dependency ->
                val keyFile = internalCertificateDirectory.resolve("${dependency.key}.key")
                val certFile = internalCertificateDirectory.resolve("${dependency.key}.crt")

                if (keyFile.exists() && certFile.exists()) return@forEach

                createCertificatePair(
                    certificateFile = certFile,
                    privateKeyFile = keyFile,
                    mainDomain = dependency.webfacingDomains.first(),
                    altDomains = dependency.webfacingDomains.drop(1)
                )
            }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun createCertificatePair(
        certificateFile: File,
        privateKeyFile: File,
        mainDomain: String,
        altDomains: List<String> = emptyList()
    ) {
        if (certificateFile.exists()) certificateFile.delete()
        if (privateKeyFile.exists()) privateKeyFile.delete()

        // Generate private key
        val privateKeyResult = Command("openssl")
            .args(
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:4096",
                "-out",
                privateKeyFile.absolutePath
            )
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        if (privateKeyResult.status != 0) {
            throw RuntimeException(
                """Failed to create private key for $mainDomain.
                |Status: ${privateKeyResult.status}
                |Output: ${privateKeyResult.stdout}
                |Error: ${privateKeyResult.stderr}
                """.trimMargin()
            )
        }

        // Create certificate signing request
        val signingRequestFile = privateKeyFile.parent!!.resolve("certificaterequest.${Uuid.random()}.csr")
        if (signingRequestFile.exists()) signingRequestFile.delete()

        val signingRequestResult = Command("openssl")
            .args(
                "req",
                "-new",
                "-key",
                privateKeyFile.absolutePath,
                "-out",
                signingRequestFile.absolutePath,
                "-subj",
                "/CN=$mainDomain"
            )
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        if (signingRequestResult.status != 0) {
            throw RuntimeException(
                """Failed to create certificate signing request for $mainDomain.
                |Status: ${signingRequestResult.status}
                |Output: ${signingRequestResult.stdout}
                |Error: ${signingRequestResult.stderr}
                """.trimMargin()
            )
        }

        // Create SAN configuration
        val sanFile = privateKeyFile.parent!!.resolve("san.${Uuid.random()}.conf")
        if (sanFile.exists()) sanFile.delete()
        sanFile.writeText(generateSanConfig(alternativeNames = listOf(mainDomain) + altDomains))

        // Sign certificate
        val certificateResult = Command("openssl")
            .args(
                "x509", "-req",
                "-in", signingRequestFile.absolutePath,
                "-CA", rootCaFile.absolutePath,
                "-CAkey", rootKeyFile.absolutePath,
                "-CAcreateserial",
                "-out", certificateFile.absolutePath,
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
                """Failed to create certificate for $mainDomain.
                |Status: ${certificateResult.status}
                |Output: ${certificateResult.stdout}
                |Error: ${certificateResult.stderr}
                """.trimMargin()
            )
        }

        // Cleanup
        if (signingRequestFile.exists()) signingRequestFile.delete()
        if (sanFile.exists()) sanFile.delete()
    }

    companion object {
        fun isValidPair(certificateFile: File, privateKeyFile: File): Boolean {
            if (!certificateFile.exists()) return false
            if (!privateKeyFile.exists()) return false

            val certificatePublicKey = Command("openssl")
                .args(
                    "x509",
                    "-pubkey",
                    "-noout",
                    "-in",
                    certificateFile.absolutePath
                )
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .spawn()
                .waitWithOutput()

            if (certificatePublicKey.status != 0) {
                return false
            }

            val privateKeyPublicKey = Command("openssl")
                .args(
                    "pkey",
                    "-pubout",
                    "-in",
                    privateKeyFile.absolutePath
                )
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .spawn()
                .waitWithOutput()

            if (privateKeyPublicKey.status != 0) {
                return false
            }

            return certificatePublicKey.stdout!!.trim() ==
                    privateKeyPublicKey.stdout!!.trim()
        }

        /**
         * Checks whether [certificateFile] was signed by [rootCaFile] and is currently valid
         * (not expired, not yet valid). Does not check whether the root CA itself is trusted
         * by the system, use [isTrusted] for that.
         */
        fun isValidChild(rootCaFile: File, certificateFile: File): Boolean {
            if (!rootCaFile.exists()) return false
            if (!certificateFile.exists()) return false

            val verifyResult = Command("openssl")
                .args(
                    "verify",
                    "-CAfile",
                    rootCaFile.absolutePath,
                    certificateFile.absolutePath
                )
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .spawn()
                .wait()

            return verifyResult == 0
        }

        /**
         * Returns all domains the certificate is issued for. Contains the subject alternative names
         * as well as the common name of the subject.
         */
        fun getDomains(certificateFile: File): List<String> {
            if (!certificateFile.exists()) return emptyList()

            val certificateResult = Command("openssl")
                .args(
                    "x509",
                    "-noout",
                    "-text",
                    "-in",
                    certificateFile.absolutePath
                )
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .spawn()
                .waitWithOutput()

            if (certificateResult.status != 0) return emptyList()

            val lines = certificateResult.stdout.orEmpty().lines()
            val domains = mutableListOf<String>()

            lines.forEachIndexed { index, line ->
                val trimmedLine = line.trim()
                when {
                    // ex: "X509v3 Subject Alternative Name:" followed by "DNS:a.test, DNS:b.test"
                    trimmedLine.startsWith("X509v3 Subject Alternative Name") -> {
                        lines.getOrNull(index + 1)
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.startsWith("DNS:") }
                            ?.forEach { domains += it.removePrefix("DNS:").trim() }
                    }

                    // ex: "Subject: CN=a.test". The common name is only a domain on leaf
                    // certificates, CAs use it as a display name ("Werkbank Root CA").
                    trimmedLine.startsWith("Subject:") -> {
                        trimmedLine.removePrefix("Subject:")
                            .split(",")
                            .map { it.trim() }
                            .firstOrNull { it.startsWith("CN=") || it.startsWith("CN =") }
                            ?.substringAfter("=")
                            ?.trim()
                            ?.takeIf { it.contains(".") && !it.contains(" ") }
                            ?.let { domains += it }
                    }
                }
            }

            return domains.filter { it.isNotBlank() }.distinct()
        }

        /**
         * Checks whether the certificate can be validated against the trust store of the system,
         * meaning the issuing CA is installed and trusted.
         */
        fun isTrusted(certificateFile: File): Boolean {
            if (!certificateFile.exists()) return false
            return isCertificateTrustedBySystem(certificateFile)
        }
    }
}

expect fun installRootCa(rootCaFile: File, sudoManager: SudoManager)
expect fun isCertificateTrustedBySystem(certificateFile: File): Boolean
expect fun uninstallRootCa(fingerprint: String, sudoManager: SudoManager)
expect suspend fun getInstalledRootCAs(sudoManager: SudoManager): List<InstalledRootCa>

data class InstalledRootCa(
    val fingerprint: String,
    val name: String,
)