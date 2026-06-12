package app.werkbank.app.login

import app.werkbank.config.AppConfig
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.date.*
import org.koin.ktor.ext.inject

fun Route.logout() {

    val appConfig by inject<AppConfig>()

    get {
        call.response.cookies.append(
            name = "werkbank-token",
            value = "",
            domain = call.request.host(),
            path = "/",
            secure = true,
            httpOnly = true,
            maxAge = 0,
            expires = GMTDate(0),
        )

        call.respondRedirect(
            url = "https://${appConfig.appDomain}",
            permanent = false,
        )
    }
}