package app.werkbank.app.cli.update

import app.werkbank.data.repository.CliBinaryRepository
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.downloadCli() {

    val cliBinaryRepository by inject<CliBinaryRepository>()

    get {
        val channel = call.parameters["channel"]!!
        val variant = call.parameters["variant"]!!

        val file = cliBinaryRepository.getCliBinary(variant, channel) ?: return@get call.respond(message = "File not found", status =  HttpStatusCode.NotFound)

        call.respondFile(file)
    }
}