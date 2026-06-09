package app.werkbank.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("domain_suffix") val domainSuffix: String,
    @SerialName("app_domain") val appDomain: String,
    @SerialName("database") val database: Database,
    @SerialName("github") val github: GitHub,
    @SerialName("cli") val cli: Cli,
    @SerialName("jwt") val jwt: Jwt,
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
}