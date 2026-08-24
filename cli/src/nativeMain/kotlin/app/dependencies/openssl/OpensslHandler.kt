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

    val certificatesFolder = storageRoot.resolve("certificates").apply {
        if (!exists()) mkdir()
    }

    /** Certificates for werkbank's own services, signed by [rootCaFile]. */
    val internalCertificateDirectory = certificatesFolder.resolve("internal")

    /** Certificates that were issued elsewhere, e.g. downloaded from the werkbank cloud. */
    val externalCertificateDirectory = certificatesFolder.resolve("external")

    val rootCaFile = certificatesFolder.resolve("rootCA.crt")
    val rootKeyFile = certificatesFolder.resolve("rootCA.key")
    val keyStoreFile = certificatesFolder.resolve("keystore.jks")
    val keyStorePassword = "changeit"

    /** Base domains the root CA is created for, written as subject alternative names. */
    val rootCaDomains get() = listOf(DEFAULT_BASE_DOMAIN, WERKBANK_BASE_DOMAIN)

    private val rootCaCommonName get() = if (isDevMode) "Werkbank Dev Root CA" else "Werkbank Root CA"

    fun certificateFileOf(dependency: AppDependency) =
        internalCertificateDirectory.resolve("${dependency.key}.crt")

    fun privateKeyFileOf(dependency: AppDependency) =
        internalCertificateDirectory.resolve("${dependency.key}.key")

    suspend fun initialize() {
        val opensslInstalled = withContext(Dispatchers.IO) {
            Command("which")
                .args("openssl")
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
                .spawn()
                .wait() == 0
        }
        if (!opensslInstalled) {
            isOpensslAvailable.complete(false)
            return
        }

        if (!isRootCaSetUp()) createRootCa()
        createInternalCertificates()

        isOpensslAvailable.complete(true)
    }

    fun isRootCaSetUp(): Boolean {
        if (!rootCaFile.exists()) return false
        if (!rootKeyFile.exists()) return false
        if (!keyStoreFile.exists()) return false
        return true
    }

    suspend fun createRootCa() {
        println(buildStyledString { cyan { +"Creating root CA certificate" } })
        println()

        step(1, "Generating private key (4096-bit RSA)") {
            runOpenssl("genrsa", "-out", rootKeyFile.absolutePath, "4096")
        }
        println(buildStyledString {
            +REPLACE_LINE
            +"   "
            green { +"$CHECK Private key generated" }
        })
        println()

        val csrConfigFile = File.getTempDirectory().resolve("root-csr.csr")
        csrConfigFile.writeText(csrRequestConfigFileContent(rootCaCommonName, rootCaDomains))

        step(2, "Creating self-signed root certificate") {
            runOpenssl(
                "req", "-x509", "-new", "-nodes",
                "-key", rootKeyFile.absolutePath,
                "-sha256",
                "-days", "1024",
                "-out", rootCaFile.absolutePath,
                "-config", csrConfigFile.absolutePath,
                "-extensions", "v3_req"
            )
        }
        println(buildStyledString {
            +REPLACE_LINE
            +"   "
            green { +"$CHECK Certificate created (valid for 1024 days)" }
        })

        step(3, "Creating keystore") {
            runOpenssl(
                "pkcs12", "-export",
                "-in", rootCaFile.absolutePath,
                "-inkey", rootKeyFile.absolutePath,
                "-out", keyStoreFile.absolutePath,
                "-name", rootCaCommonName,
                "-password", "pass:$keyStorePassword"
            )
        }
        println(buildStyledString {
            +REPLACE_LINE
            +"   "
            green { +"$CHECK Keystore created" }
        })
        println()

        println(buildStyledString { green { +"$CHECK Root CA created successfully" } })
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

        deleteCertificatesSignedByOldRootCa()
        promptForRootCaInstallation()
    }

    /**
     * Every leaf certificate was signed by the previous root CA and is worthless now. Deleting
     * them makes sure nothing keeps using a certificate that no longer validates.
     */
    private fun deleteCertificatesSignedByOldRootCa() {
        println(buildStyledString { cyan { +"Deleting old leaf certificates if present" } })

        dependencies.forEach { dependency ->
            val certificateFile = certificateFileOf(dependency)
            val privateKeyFile = privateKeyFileOf(dependency)
            if (certificateFile.exists()) certificateFile.delete()
            if (privateKeyFile.exists()) privateKeyFile.delete()
        }

        projectRepository.getAllProjects().forEach { project ->
            if (project.certificateFile.exists()) project.certificateFile.delete()
            if (project.privateKeyFile.exists()) project.privateKeyFile.delete()
        }
    }

    private suspend fun promptForRootCaInstallation() {
        println(buildStyledString {
            yellow { +"Do you want to install the root CA in your system now? (y/n)" }
        })

        if (readln().lowercase() != "y") {
            println(buildStyledString { gray { +"   You can install the certificate later from:" } })
            println(buildStyledString {
                gray { +"   " }
                +rootCaFile.absolutePath
            })
            println()
            return
        }

        installRootCaInSystem()
    }

    /**
     * Installs the root CA in the system trust store, replacing an older installation with the
     * same name. This is all that is needed when the certificate itself is fine but the system
     * does not know it (yet).
     */
    suspend fun installRootCaInSystem() {
        println()
        println(buildStyledString { cyan { +"Installing root CA..." } })

        val existingCa = getInstalledRootCAs(SudoManager()).firstOrNull { it.name == rootCaCommonName }
        if (existingCa != null) {
            println(buildStyledString {
                yellow { +"   Found existing CA installation: " }
                +rootCaCommonName
            })
            println(buildStyledString {
                yellow { +"   Removing old version (fingerprint: ${existingCa.fingerprint})" }
            })
            uninstallRootCa(existingCa.fingerprint, SudoManager())
        }

        installRootCa(rootCaFile, SudoManager())

        println(buildStyledString { green { +"$CHECK Root CA installed successfully" } })
        // Browsers read the system trust store once at startup.
        println(buildStyledString { yellow { +"Restart your browser so it picks up the new root CA." } })
        println()
    }

    /**
     * Creates the certificates for all web facing [dependencies]. Existing certificates are kept
     * unless [force] is set, which is needed whenever the root CA or the base domains changed.
     */
    fun createInternalCertificates(
        dependencies: List<AppDependency> = this.dependencies,
        force: Boolean = false
    ) {
        if (!internalCertificateDirectory.exists()) internalCertificateDirectory.mkdir(recursive = true)
        if (!externalCertificateDirectory.exists()) externalCertificateDirectory.mkdir(recursive = true)

        dependencies
            .filter { it.webfacingDomains.isNotEmpty() }
            .forEach { dependency ->
                val certificateFile = certificateFileOf(dependency)
                val privateKeyFile = privateKeyFileOf(dependency)

                if (!force && certificateFile.exists() && privateKeyFile.exists()) return@forEach

                createCertificatePair(
                    certificateFile = certificateFile,
                    privateKeyFile = privateKeyFile,
                    mainDomain = dependency.webfacingDomains.first(),
                    altDomains = dependency.webfacingDomains.drop(1)
                )
            }
    }

    /** Creates a certificate for [mainDomain] and [altDomains], signed by the root CA. */
    @OptIn(ExperimentalUuidApi::class)
    fun createCertificatePair(
        certificateFile: File,
        privateKeyFile: File,
        mainDomain: String,
        altDomains: List<String> = emptyList()
    ) {
        if (certificateFile.exists()) certificateFile.delete()
        if (privateKeyFile.exists()) privateKeyFile.delete()

        runOpenssl(
            "genpkey",
            "-algorithm", "RSA",
            "-pkeyopt", "rsa_keygen_bits:4096",
            "-out", privateKeyFile.absolutePath,
            failure = "Failed to create private key for $mainDomain."
        )

        val signingRequestFile = privateKeyFile.parent!!.resolve("certificaterequest.${Uuid.random()}.csr")
        val sanFile = privateKeyFile.parent!!.resolve("san.${Uuid.random()}.conf")
        sanFile.writeText(generateSanConfig(alternativeNames = listOf(mainDomain) + altDomains))

        try {
            runOpenssl(
                "req", "-new",
                "-key", privateKeyFile.absolutePath,
                "-out", signingRequestFile.absolutePath,
                "-subj", "/CN=$mainDomain",
                failure = "Failed to create certificate signing request for $mainDomain."
            )

            runOpenssl(
                "x509", "-req",
                "-in", signingRequestFile.absolutePath,
                "-CA", rootCaFile.absolutePath,
                "-CAkey", rootKeyFile.absolutePath,
                "-CAcreateserial",
                "-out", certificateFile.absolutePath,
                "-days", "365",
                "-sha256",
                "-extfile", sanFile.absolutePath,
                failure = "Failed to create certificate for $mainDomain."
            )
        } finally {
            if (signingRequestFile.exists()) signingRequestFile.delete()
            if (sanFile.exists()) sanFile.delete()
        }
    }

    /** Prints a numbered step of [createRootCa] together with the command it runs. */
    private inline fun step(number: Int, description: String, block: () -> List<String>) {
        println(buildStyledString {
            blue { +"Step $number" }
            +": $description"
        })
        val arguments = block()
        print(buildStyledString {
            +"   $ "
            gray { +"openssl ${arguments.joinToString(" ")}" }
        })
    }

    /**
     * Runs openssl and throws with the full output when it fails. Returns the arguments so
     * [step] can show what was executed.
     */
    private fun runOpenssl(vararg arguments: String, failure: String = "openssl failed."): List<String> {
        val result = Command("openssl")
            .args(arguments.toList())
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        if (result.status != 0) {
            println()
            throw RuntimeException(
                """$failure
                |Status: ${result.status}
                |Output: ${result.stdout}
                |Error: ${result.stderr}
                """.trimMargin()
            )
        }

        return arguments.toList()
    }

    companion object {
        /** Checks whether [certificateFile] and [privateKeyFile] share the same public key. */
        fun isValidPair(certificateFile: File, privateKeyFile: File): Boolean {
            if (!certificateFile.exists()) return false
            if (!privateKeyFile.exists()) return false

            val certificatePublicKey = openssl("x509", "-pubkey", "-noout", "-in", certificateFile.absolutePath)
            if (certificatePublicKey.status != 0) return false

            val privateKeyPublicKey = openssl("pkey", "-pubout", "-in", privateKeyFile.absolutePath)
            if (privateKeyPublicKey.status != 0) return false

            return certificatePublicKey.stdout!!.trim() == privateKeyPublicKey.stdout!!.trim()
        }

        /**
         * Checks whether [certificateFile] was signed by [rootCaFile] and is currently valid
         * (not expired, not yet valid). Does not check whether the root CA itself is trusted
         * by the system, use [isTrusted] for that.
         */
        fun isValidChild(rootCaFile: File, certificateFile: File): Boolean {
            if (!rootCaFile.exists()) return false
            if (!certificateFile.exists()) return false

            return openssl(
                "verify",
                "-CAfile", rootCaFile.absolutePath,
                certificateFile.absolutePath
            ).status == 0
        }

        /** Checks whether the certificate is currently within its validity period. */
        fun isExpired(certificateFile: File): Boolean {
            if (!certificateFile.exists()) return false
            return openssl("x509", "-checkend", "0", "-noout", "-in", certificateFile.absolutePath).status != 0
        }

        /**
         * Checks whether the certificate can be validated against the trust store of the system,
         * meaning the issuing CA is installed and trusted.
         */
        fun isTrusted(certificateFile: File): Boolean {
            if (!certificateFile.exists()) return false
            return isCertificateTrustedBySystem(certificateFile)
        }

        /**
         * Returns all domains the certificate is issued for. Contains the subject alternative
         * names as well as the common name of the subject.
         */
        fun getDomains(certificateFile: File): List<String> {
            if (!certificateFile.exists()) return emptyList()

            val result = openssl("x509", "-noout", "-text", "-in", certificateFile.absolutePath)
            if (result.status != 0) return emptyList()

            val lines = result.stdout.orEmpty().lines()
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

        private fun openssl(vararg arguments: String) = Command("openssl")
            .args(arguments.toList())
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()
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
