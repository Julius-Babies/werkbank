package app.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WerkbankConfig(
    @SerialName("werkbank-cloud-domain") val werkbankCloudDomain: String = "wbspace.app",
    @SerialName("projects") val projects: List<Project>? = null,
    @SerialName("android-dns") val androidDns: AndroidDnsConfig = AndroidDnsConfig(),
    @SerialName("keycloak") val keycloak: KeycloakConfig = KeycloakConfig(),
    @SerialName("auth") val auth: Auth? = null,
) {
    @Serializable
    data class Project(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        @SerialName("path") val path: String,
        @SerialName("submodules") val submodules: List<Submodule>,
        @SerialName("services") val services: List<Service>,
    ) {
        @Serializable
        data class Submodule(
            @SerialName("name") val name: String,
            @SerialName("path") val path: String,
        )

        @Serializable
        data class Service(
            @SerialName("name") val name: String,
            @SerialName("state") val serviceState: ServiceState,
        ) {
            @Serializable
            enum class ServiceState {
                @SerialName("docker") Docker,
                @SerialName("local") Local,
                @SerialName("disabled") Disabled
            }
        }
    }

    @Serializable
    data class AndroidDnsConfig(
        @SerialName("enabled") val enabled: Boolean = true
    )

    @Serializable
    data class KeycloakConfig(
        @SerialName("image") val image: String = KEYCLOAK_DEFAULT_IMAGE,
    ) {
        companion object {
            const val KEYCLOAK_DEFAULT_IMAGE = "quay.io/keycloak/keycloak:26.6"
        }
    }

    @Serializable
    data class Auth(
        @SerialName("username") val username: String,
        @SerialName("bearer") val bearer: String,
    )
}