package app.werkbank

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.path
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    runBlocking {
        AppCommand().main(args)
    }
}

class AppCommand: SuspendingCliktCommand("server") {
    val storageDirectory by option("--storage-directory", help = "Path to store data in")
        .path(mustExist = true, mustBeWritable = true, mustBeReadable = true)
        .default(File("./data").toPath())

    val bindHost by option("--bind-host", help = "Host to bind to")
        .optionalValue("")

    val port by option("--port", help = "Port to bind to")
        .default("8080")
        .validate { require(it.toIntOrNull() != null) { "Port must be a number" } }

    override suspend fun run() {

        embeddedServer(
            factory = Netty,
            port = port.toInt(),
            host = bindHost ?: "0.0.0.0",
            module = { rootModule(storageDirectory.toFile()) }
        ).start(wait = true)
    }
}