package app.werkbank.app.login.github

import app.werkbank.database.User
import app.werkbank.plugins.auth.redirectToSession
import es.jvbabi.authentikt.core.AuthentiktInstance
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.loginWithGitHub() {
    val authentikt by inject<AuthentiktInstance<User>>()

    get("/jump") {
        val session = authentikt.createNewSession()
        call.redirectToSession(session)
    }
}