package app.werkbank.app.login

import app.werkbank.config.AppConfig
import app.werkbank.database.User
import app.werkbank.plugins.auth.redirectToSession
import es.jvbabi.authentikt.core.AuthentiktInstance
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import org.koin.ktor.ext.inject

val redirectAttribute = AttributeKey<String>("redirect")

fun Route.login() {

    val appConfig by inject<AppConfig>()
    val authentikt by inject<AuthentiktInstance<User>>()

    get {
        val redirectUri = Url(call.request.queryParameters["redirect"]!!)
        if (!redirectUri.host.endsWith(appConfig.appDomain)) {
            call.respond(
                message = "Invalid redirect uri",
                status = HttpStatusCode.BadRequest
            )
            return@get
        }

        val session = authentikt.createNewSession()
        session.attributes[redirectAttribute] = redirectUri.toString()

        call.redirectToSession(session)
    }
}