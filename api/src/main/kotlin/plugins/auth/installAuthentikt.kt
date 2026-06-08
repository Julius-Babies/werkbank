package app.werkbank.plugins.auth

import app.werkbank.app.dns.DnsManager
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
import es.jvbabi.authentikt.core.session.SessionDestination
import es.jvbabi.authentikt.core.step.plugins.builtin.DonePlugin
import es.jvbabi.authentikt.core.step.plugins.builtin.OIDCPlugin
import es.jvbabi.authentikt.core.step.plugins.builtin.UserInfo
import io.ktor.client.call.*
import io.ktor.server.application.*
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

fun Application.installAuthentikt() {
    val appConfig by inject<AppConfig>()
    val db by inject<DatabaseManager>()
    val dnsManager by inject<DnsManager>()

    val authentikt = installAuthentikt {
        baseUrl = "https://${appConfig.domainSuffix}"
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

                val user = run {
                    val existingUser = db.query { User.find { Users.username eq username }.firstOrNull() }
                    val userDomain = username.lowercase() + "." + appConfig.domainSuffix
                    if (existingUser == null) {
                        val user = db.query { User.new { this.username = username; this.githubToken = accessToken } }
                        dnsManager.createRecord(userDomain)
                        return@run user
                    } else {
                        db.query { existingUser.githubToken = accessToken }
                    }
                    return@run existingUser
                }

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

                cookie(
                    name = "SessionToken",
                    value = token,
                    validFor = tokenValidity,
                )

                val userDomain = user.username.lowercase() + "." + appConfig.domainSuffix
                redirect("https://$userDomain")
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
