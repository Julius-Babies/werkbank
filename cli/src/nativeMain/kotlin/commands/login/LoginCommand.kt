package commands.login

import app.config.MainConfig
import app.config.WerkbankConfig
import app.dependencies.reverse_proxy.TraefikManager
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import http.httpClient
import io.ktor.client.call.*
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.getValue
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class LoginCommand : SuspendingCliktCommand("login"), KoinComponent {

    private val mainConfig by inject<MainConfig>()
    private val traefikManager by inject<TraefikManager>()

    override suspend fun run() {
        val client = httpClient()

        val deviceCodeResponse = client.submitForm(
            url = "https://werkbank.werkbank.space/oauth/device/code",
            formParameters = Parameters.build {
                append("client_id", "werkbank-cli")
                append("scope", "openid profile email")
            }
        )
        if (deviceCodeResponse.status != HttpStatusCode.OK) {
            println("Failed to get device code: ${deviceCodeResponse.status}")
            println(deviceCodeResponse.bodyAsText())
            return
        }

        val deviceCode = deviceCodeResponse.body<DeviceAuthorizationResponse>()

        println()
        println(buildStyledString {
            +"    "
            green {
                bold {
                    +"Werkbank.CLI"
                }
                +" login"
            }
        })
        println(buildStyledString {
            +"    "
            gray { +"Visit " }
            +">> "
            bold {
                yellow {
                    underline { +(deviceCode.verificationUriComplete ?: deviceCode.verificationUri) }
                }
            }
            +" <<"
            if (deviceCode.verificationUriComplete == null) {
                +" and enter "
                blue { +deviceCode.userCode }
            }
        })

        println()

        while (true) {
            val response = client.submitForm(
                url = "https://werkbank.werkbank.space/oauth/token",
                formParameters = Parameters.build {
                    append(
                        "grant_type",
                        "urn:ietf:params:oauth:grant-type:device_code"
                    )
                    append("device_code", deviceCode.deviceCode)
                    append("client_id", "werkbank-cli")
                }
            )

            var failed = false

            val currentTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            print(buildStyledString {
                +"\u001B[2K\r"
                yellow { +currentTime.format(LocalDateTime.Format {
                    hour(Padding.ZERO)
                    char(':')
                    minute(Padding.ZERO)
                    char(':')
                    second(Padding.ZERO)
                }) }
                +" "
                if (response.status == HttpStatusCode.OK) {
                    green {
                        +"Authorized Werkbank-CLI"
                    }
                } else if (response.status == HttpStatusCode.BadRequest) {
                    val body = response.body<Map<String, String?>>()
                    if (body["error"] == "authorization_pending") {
                        gray { +"Waiting for authorization..." }
                    } else if (body["error"] == "slow_down") {
                        gray { +"Waiting for authorization... (slow down)" }
                    } else if (body["error"] == "expired_token") {
                        failed = true
                        red {
                            +"Authorization expired. Please run "
                            blue { +"wb login" }
                            +" again."
                        }
                    }
                }
            })

            if (failed) exitProcess(1)

            if (response.status == HttpStatusCode.OK) {
                println()
                val body = response.body<TokenResponse>()

                val meResponse = client.get("https://werkbank.werkbank.space/api/me") {
                    bearerAuth(body.accessToken)
                }
                val meResponseBody = meResponse.body<MeResponse>()

                mainConfig.updateConfig {
                    it.copy(
                        auth = WerkbankConfig.Auth(
                            username = meResponseBody.username,
                            bearer = body.accessToken,
                        )
                    )
                }
                println(buildStyledString {
                    green { +"Authorized as " }
                    bold { +meResponseBody.username }
                    +"."
                })
                println(buildStyledString {
                    gray { +"Generating proxy config..." }
                })
                traefikManager.generateProxyConfig()
                println(buildStyledString {
                    green { +"Login successful!" }
                })
                break
            }

            delay(deviceCode.interval.seconds)
        }
    }
}

