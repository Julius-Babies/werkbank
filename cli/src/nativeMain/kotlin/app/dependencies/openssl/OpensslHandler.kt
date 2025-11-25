package app.dependencies.openssl

import app.storage.storageRoot
import com.kgit2.kommand.exception.KommandException
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import util.CHECK
import util.REPLACE_LINE
import util.buildStyledString

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
            throw RuntimeException("""Failed to create root CA key.
                |Status: ${keyFileResult.status}
                |Output: ${keyFileResult.stdout}
                |Error: ${keyFileResult.stderr}
            """.trimMargin())
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
            .args("req", "-x509", "-new", "-nodes", "-key", rootKeyFile.absolutePath, "-sha256", "-days", "1024", "-out", rootCaFile.absolutePath, "-subj", "/C=DE/ST=Saxony/L=Dresden/O=Werkbank/OU=Dev/CN=Werkbank Root CA")
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)
            .spawn()
            .waitWithOutput()
        if (certFileResult.status != 0) {
            println()
            throw RuntimeException("""Failed to create root CA certificate.
                |Status: ${certFileResult.status}
                |Output: ${certFileResult.stdout}
                |Error: ${certFileResult.stderr}
            """.trimMargin())
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
    }
}