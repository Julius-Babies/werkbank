package app.werkbank.app.login.github

import app.werkbank.config.AppConfig
import app.werkbank.database.User
import es.jvbabi.authentikt.core.AuthentiktInstance
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.loginWithGitHub() {
    val appConfig by inject<AppConfig>()
    val authentikt by inject<AuthentiktInstance<User>>()

    get("/jump") {
        val session = authentikt.createNewSession()
        val redirectUrl = URLBuilder("https://${appConfig.appDomain}").apply {
            appendPathSegments("auth")
            parameters.append("_authentikt_flow_active", "true")
            parameters.append("_authentikt_session_id", session.sessionId)
        }
        call.respondRedirect(redirectUrl.buildString(), permanent = false)
    }
}