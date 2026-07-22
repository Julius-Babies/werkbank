package app.werkbank.plugins.proxy

import app.werkbank.config.AppConfig
import app.werkbank.database.*
import app.werkbank.util.sha256
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.server.application.*
import io.ktor.server.request.*
import org.jetbrains.exposed.v1.core.eq
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid

const val ACCESS_TOKEN_HEADER_NAME = "Werkbank-Access-Token"

suspend fun ApplicationCall.checkRequestAuthorization(): AuthorizationResult {
    val appConfig by application.inject<AppConfig>()
    val db by inject<DatabaseManager>()

    val project = attributes[targetProject]
    if (project.accessState == Project.AccessState.Open) return AuthorizationResult.Success

    val authCookieName = "werkbank-project-auth-token-${project.id.value.toHexString()}"
    val cookieValue = request.cookies[authCookieName]

    val accessToken = cookieValue?.takeIf { it.isNotBlank() } ?: request.header(ACCESS_TOKEN_HEADER_NAME)
        ?: return AuthorizationResult.Failure.NoAccessToken

    val jwtVerifier = JWT
        .require(Algorithm.HMAC256(appConfig.jwt.secret))
        .withAudience("werkbank-project-${project.id.value.toHexString()}")
        .withIssuer("werkbank")
        .build()

    try {
        val jwt = jwtVerifier.verify(accessToken)

        if (jwt.audience.first() == "werkbank-projects") {
            val accessKey = db.query { AccessKey.find { AccessKeys.key eq accessToken.sha256() }.firstOrNull() }
                ?: return AuthorizationResult.Failure.InvalidAccessToken
            val user = db.query { accessKey.createdBy }
            val isOwner = db.query { project.owner.id.value == user.id.value }
            return if (isOwner) AuthorizationResult.Success
            else AuthorizationResult.Failure.InvalidAccessToken
        }

        if (jwt.audience.first() != "werkbank-project-${project.id.value.toHexString()}") return AuthorizationResult.Failure.InvalidAccessToken
        when (jwt.getClaim("source").asString()) {
            "user" -> {
                val user = db.query { User.findById(Uuid.parse(jwt.getClaim("user_id").asString())) }
                if (user == null) return AuthorizationResult.Failure.InvalidAccessToken
                val isOwner = db.query { project.owner.id.value == user.id.value }
                return if (isOwner) AuthorizationResult.Success
                else AuthorizationResult.Failure.InvalidAccessToken
            }
            "password" -> {
                if (project.accessState == Project.AccessState.Disabled) return AuthorizationResult.Failure.PasswordDisabled
                val passwordId = jwt.getClaim("password_id").asString()
                val isPasswordValid = db.query { project.passwords.any { password -> password.password.id.value.toHexString() == passwordId } }
                return if (isPasswordValid) AuthorizationResult.Success
                else AuthorizationResult.Failure.InvalidAccessToken
            }
            else -> return AuthorizationResult.Failure.InvalidAccessToken
        }
    } catch (_: JWTVerificationException) {
        return AuthorizationResult.Failure.InvalidAccessToken
    }
}

sealed class AuthorizationResult {
    data object Success : AuthorizationResult()
    sealed class Failure : AuthorizationResult(), SimpleError {
        data object NoAccessToken : Failure() {
            override val code: String = "ERR_NO_ACCESS_TOKEN"
            override val message: String =
                "This project requires an access token. If you're in a browser, log into a permitted werkbank account in this session. You can also specify a header called $ACCESS_TOKEN_HEADER_NAME and set it to an access token."
        }

        data object InvalidAccessToken : Failure() {
            override val code: String = "ERR_INVALID_ACCESS_TOKEN"
            override val message: String = "This access token is invalid."
        }

        data object PasswordDisabled : Failure() {
            override val code: String = "ERR_PASSWORD_DISABLED"
            override val message: String = "The password has been disabled."
        }
    }
}