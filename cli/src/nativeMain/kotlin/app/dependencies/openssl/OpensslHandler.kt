package app.dependencies.openssl

import app.SudoManager
import app.storage.storageRoot
import com.kgit2.kommand.exception.KommandException
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import es.jvbabi.kfile.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import util.CHECK
import util.REPLACE_LINE
import util.buildStyledString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class OpensslHandler {
    val isOpensslAvailable = CompletableDeferred<Boolean>()

    suspend fun initialize() {
        try {
            withContext(Dispatchers.IO) {
                Command("openssl")
                    .stdout(Stdio.Null)
                    .stderr(Stdio.Pipe)
                    .spawn()
                    .wait()
            }
            isOpensslAvailable.complete(true)
        } catch (e: KommandException) {
            if (e.message?.startsWith("No such file or directory") == true) {
                isOpensslAvailable.complete(false)
                return
            }
            throw e
        }

        if (!isRootCaSetUp()) createRootCa()
    }

    val certificatesFolder = storageRoot.resolve("certificates").apply { if (!exists()) mkdir() }
    val rootCaFile = certificatesFolder.resolve("rootCA.crt")
    val rootKeyFile = certificatesFolder.resolve("rootCA.key")

    fun isRootCaSetUp(): Boolean {
        if (!rootCaFile.exists()) return false
        if (!rootKeyFile.exists()) return false

        return true
    }

    fun createRootCa() {
        println(buildStyledString { cyan { +"Creating root CA" } })
        println(buildStyledString {
            blue { +"Step 1" }
            +": Generating private key"
        })
        print(buildStyledString {
            +"  $ "
            gray {
                +"openssl genrsa -out ${rootKeyFile.absolutePath} 4096"
            }
        })
        val keyFileResult = Command("openssl")
            .args("genrsa", "-out", rootKeyFile.absolutePath, "4096")
            .stdout(Stdio.Null)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()
        if (keyFileResult.status != 0) {
            println()
            throw RuntimeException(
                """Failed to create root CA key.
                |Status: ${keyFileResult.status}
                |Output: ${keyFileResult.stdout}
                |Error: ${keyFileResult.stderr}
            """.trimMargin()
            )
        }

        println(buildStyledString {
            +REPLACE_LINE
            blue { +"Step 2" }
            +": Certificate signing request"
        })
        print(buildStyledString {
            +"  $ "
            gray {
                +"openssl req -x509 -new -nodes -key ${rootKeyFile.absolutePath} -sha256 -days 1024 -out ${rootCaFile.absolutePath} -subj \"/C=DE/ST=Berlin/L=Berlin/O=Werkbank/OU=Dev/CN=Werkbank Root CA\""
            }
        })
        val certFileResult = Command("openssl")
            .args(
                "req",
                "-x509",
                "-new",
                "-nodes",
                "-key",
                rootKeyFile.absolutePath,
                "-sha256",
                "-days",
                "1024",
                "-out",
                rootCaFile.absolutePath,
                "-subj",
                "/C=DE/ST=Saxony/L=Dresden/O=Werkbank/OU=Dev/CN=Werkbank Root CA"
            )
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
            green { +"$CHECK Root CA created successfully" }
        })
        println(buildStyledString {
            yellow {
                +"‣ You can now install the file located at "
                bold {
                    +rootCaFile.absolutePath
                }
                +" in your browser or OS"
            }
        })
        println("Do you want to install the root CA now? (y/n)")
        val response = readln()
        if (response.lowercase() == "y") installRootCa(rootCaFile, SudoManager())
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

        val privateKeyResult = Command("openssl")
            .args("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:4096", "-out", privateKeyFile.absolutePath)
            .stdout(Stdio.Null)
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

        val signingRequestFile = privateKeyFile.parent!!.resolve("certificaterequest.${Uuid.random()}.csr")
        if (signingRequestFile.exists()) signingRequestFile.delete()
        val signingRequestResult = Command("openssl")
            .args("req", "-new", "-key", privateKeyFile.absolutePath, "-out", signingRequestFile.absolutePath, "-subj", "/CN=$mainDomain")
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()

        val sanFile = privateKeyFile.parent!!.resolve("san.${Uuid.random()}.conf")
        if (sanFile.exists()) sanFile.delete()
        sanFile.writeText(generateSanConfig(alternativeNames = listOf(mainDomain) + altDomains))

        if (signingRequestResult.status != 0) {
            throw RuntimeException(
                """Failed to create certificate signing request for $mainDomain.
                    |Status: ${signingRequestResult.status}
                    |Output: ${signingRequestResult.stdout}
                    |Error: ${signingRequestResult.stderr}
                """.trimMargin()
            )
        }

        val certificateResult = Command("openssl")
            .args("x509", "-req", "-in", signingRequestFile.absolutePath, "-CA", rootCaFile.absolutePath, "-CAkey", rootKeyFile.absolutePath, "-CAcreateserial", "-out", certificateFile.absolutePath, "-days", "365", "-sha256", "-extfile", sanFile.absolutePath)
            .stdout(Stdio.Null)
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
    }
}

expect fun installRootCa(rootCaFile: File, sudoManager: SudoManager)