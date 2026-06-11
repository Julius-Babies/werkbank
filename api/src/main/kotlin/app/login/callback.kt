package app.werkbank.app.login

import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.User
import app.werkbank.plugins.auth.tokenMap
import com.auth0.jwt.JWT
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

fun Route.callback() {

    val db by inject<DatabaseManager>()
    val appConfig by inject<AppConfig>()

    get {
        val code = call.parameters["code"]!!
        val result = tokenMap[code]!!

        val jwt = JWT.decode(result.token)
        val jwtUserId = Uuid.parse(jwt.getClaim("sub").asString())
        val user = db.query { User.findById(jwtUserId) }
        if (user == null) {
            call.respondText("User not found")
            return@get
        }
        val host = call.request.host()
        val userDomain = host
            .removeSuffix(appConfig.domainSuffix)
            .removeSuffix(".")
            .split(".")
            .last()
            .lowercase()

        if (user.username.lowercase() != userDomain) {
            call.respondText("Invalid user domain")
            return@get
        }

        call.response.cookies.append(Cookie(
            name = "werkbank-token",
            value = result.token,
            path = "/",
            secure = true,
            httpOnly = true,
            domain = call.request.host(),
        ))
        call.respondRedirect(result.redirectUrl, permanent = false)
    }
}