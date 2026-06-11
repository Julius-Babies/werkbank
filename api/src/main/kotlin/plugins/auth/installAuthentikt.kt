package app.werkbank.plugins.auth

import app.werkbank.app.certificates.CertificateManager
import app.werkbank.app.dns.DnsManager
import app.werkbank.app.login.redirectAttribute
import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import app.werkbank.database.User
import app.werkbank.database.Users
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import es.jvbabi.authentikt.core.AuthentiktInstance
import es.jvbabi.authentikt.core.AuthentiktUser
import es.jvbabi.authentikt.core.config.OAuthAccessToken
import es.jvbabi.authentikt.core.config.OAuthDeviceFlowAuthorizationResult
import es.jvbabi.authentikt.core.installAuthentikt
import es.jvbabi.authentikt.core.session.Session
import es.jvbabi.authentikt.core.session.SessionDestination
import es.jvbabi.authentikt.core.step.plugins.builtin.DonePlugin
import es.jvbabi.authentikt.core.step.plugins.builtin.OIDCPlugin
import es.jvbabi.authentikt.core.step.plugins.builtin.UserInfo
import io.ktor.client.call.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import org.jetbrains.exposed.v1.core.eq
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

private fun User.toAuthentiktUser() = object : AuthentiktUser<User>(this) {
    override suspend fun getDisplayName(): String = username
    override suspend fun getUsername(): String = username
    override suspend fun getEmail(): String = username
}

/**
 * Maps a code to a token.
 * When authenticating using the application domain (e.g. wbspace.app) we need to redirect to the destination domain
 * (e.g. <username>.wbspace.app/auth/callback) since the cookie in which the token is stored is only valid for the
 * destination domain. To avoid putting the token in the url (like ?token=<token>) we create a temporary code which
 * is the key and the token is the value.
 */
val tokenMap = mutableMapOf<String, AuthRedirect>()

data class AuthRedirect(
    val token: String,
    val redirectUrl: String,
)

fun Application.installAuthentikt() {
    val appConfig by inject<AppConfig>()
    val db by inject<DatabaseManager>()
    val dnsManager by inject<DnsManager>()
    val certificateManager by inject<CertificateManager>()

    val authentikt = installAuthentikt {
        baseUrl = "https://${appConfig.appDomain}"
        apiPrefix = "/api"
        uiLoginBaseUrl = "$baseUrl/auth"

        val githubOauthPlugin = OIDCPlugin {
            clientId = appConfig.github.clientId
            clientSecret = appConfig.github.clientSecret
            authorizationEndpoint = "https://github.com/login/oauth/authorize"
            tokenEndpoint = "https://github.com/login/oauth/access_token"
            userInfoEndpoint = "https://api.github.com/user"

            onUserInfo { response, accessToken ->
                val fields = response.body<Map<String, String?>>()
                val username = fields["login"]!!

                val userDomain = username.lowercase() + "." + appConfig.domainSuffix

                val user = run {
                    val existingUser = db.query { User.find { Users.username eq username }.firstOrNull() }
                    if (existingUser == null) {
                        val user = db.query {
                            User.new {
                                this.username = username
                                this.githubToken = accessToken
                                this.email = fields["email"]
                                this.profileImageUrl = fields["avatar_url"]
                            }
                        }
                        dnsManager.createRecord("*.$userDomain")

                        return@run user
                    } else {
                        db.query { existingUser.githubToken = accessToken }
                    }
                    return@run existingUser
                }

//                if (db.query { user.certificates.empty() }) {
//                    val id = Uuid.random()
//                    val certificateFile = File(System.getProperty("java.io.tmpdir"), "certificate-$id.crt")
//                    val keyFile = File(System.getProperty("java.io.tmpdir"), "key-$id.key")
//
//                    certificateManager.requestCertificate(
//                        domains = listOf("*.$userDomain"),
//                        targetCertFile = certificateFile,
//                        targetKeyFile = keyFile,
//                    )
//
//                    db.query {
//                        Certificate.new {
//                            this.user = user
//                            this.privateKey = ExposedBlob(keyFile.readBytes())
//                            this.certificate = ExposedBlob(certificateFile.readBytes())
//                        }
//                    }
//                }

                return@onUserInfo UserInfo.Result.Success(user.toAuthentiktUser())
            }

            scopes("openid", "profile", "email")
        }

        val donePlugin = DonePlugin {

            val tokenValidity = 60.days
            fun createToken(user: User): String = JWT.create()
                    .withAudience("werkbank")
                    .withIssuer("werkbank")
                    .withClaim("username", user.username)
                    .withClaim("sub", user.id.value.toHexString())
                    .withExpiresAt((Clock.System.now() + tokenValidity).toJavaInstant())
                    .sign(Algorithm.HMAC256(appConfig.jwt.secret))

            onSuccess { session, user ->
                if (session.destination is SessionDestination.DeviceFlow) return@onSuccess

                val token = createToken(user)
                val code = (1..3).map { Uuid.random().toHexString() }.joinToString("")
                val userDomain = user.username.lowercase() + "." + appConfig.domainSuffix
                tokenMap[code] = AuthRedirect(
                    token = token,
                    redirectUrl = session.attributes[redirectAttribute] ?: "https://$userDomain"
                )

                redirect("https://$userDomain/api/login/callback?code=$code")
            }

            onOAuthSuccess { _, user ->
                return@onOAuthSuccess OAuthAccessToken(
                    accessToken = createToken(user),
                    refreshToken = null,
                    expiresIn = tokenValidity,
                )
            }
        }

        oauth {
            onDeviceFlow { clientId ->
                return@onDeviceFlow when (clientId) {
                    appConfig.cli.clientId -> OAuthDeviceFlowAuthorizationResult.Application(
                        clientId = clientId,
                        name = "Werkbank CLI",
                        deviceCode = Uuid.random().toString(),
                        userCode = generateUserCode()
                    )
                    else -> OAuthDeviceFlowAuthorizationResult.Error("Invalid client id")
                }
            }
        }

        install(githubOauthPlugin)
        install(donePlugin)

        authorization { session ->
            session.identifiedUser ?: return@authorization githubOauthPlugin
            return@authorization donePlugin
        }
    }

    loadKoinModules(module { single<AuthentiktInstance<User>> { authentikt } })
}


suspend fun ApplicationCall.redirectToSession(session: Session<*>) {
    val appConfig by inject<AppConfig>()

    val redirectUrl = URLBuilder("https://${appConfig.appDomain}").apply {
        appendPathSegments("auth")
        parameters.append("_authentikt_flow_active", "true")
        parameters.append("_authentikt_session_id", session.sessionId)
    }
    this.respondRedirect(redirectUrl.buildString(), permanent = false)
}
