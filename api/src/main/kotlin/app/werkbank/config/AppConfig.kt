package app.werkbank.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("domain_suffix") val domainSuffix: String,
    @SerialName("app_domain") val appDomain: String,
    @SerialName("local_web_root") val localWebRoot: String,
    @SerialName("database") val database: Database,
    @SerialName("github") val github: GitHub,
    @SerialName("cli") val cli: Cli,
    @SerialName("jwt") val jwt: Jwt,
    @SerialName("dns") val dns: Dns,
    @SerialName("cloudflare") val cloudflare: Cloudflare? = null,
    @SerialName("tls") val tls: Tls,
    @SerialName("otel") val otel: Otel,
) {
    @Serializable
    data class Database(
        @SerialName("url") val url: String,
    )

    @Serializable
    data class GitHub(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String,
        @SerialName("redirect_uri") val redirectUri: String,
    )

    @Serializable
    data class Cli(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String,
    )

    @Serializable
    data class Jwt(
        @SerialName("secret") val secret: String,
    )

    @Serializable
    data class Dns(
        @SerialName("target_ip") val targetIp: String,
    )

    @Serializable
    data class Cloudflare(
        @SerialName("zone_id") val zoneId: String,
        @SerialName("api_token") val apiToken: String,
        @SerialName("domain") val domain: String,
    )

    @Serializable
    sealed class Tls {
        @Serializable
        @SerialName("self-signed")
        data class SelfSigned(
            @SerialName("root_ca") val rootCa: RootCa? = null
        ): Tls() {
            @Serializable
            data class RootCa(
                @SerialName("certificate_path") val certificatePath: String,
                @SerialName("key_path") val keyPath: String,
            )
        }

        @Serializable
        @SerialName("lets-encrypt")
        data class LetsEncrypt(
            @SerialName("keypair_path") val keypairPath: String,
            @SerialName("email") val email: String,
            @SerialName("mode") val mode: Mode,
        ): Tls() {
            @Serializable
            enum class Mode {
                @SerialName("staging") Staging,
                @SerialName("production") Production,
            }
        }
    }

    @Serializable
    data class Otel(
        @SerialName("endpoint") val endpoint: String,
        @SerialName("service_name") val serviceName: String,
    )
}