package app.dependencies.reverse_proxy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Traefik TLS configuration.
 * This represents a config file used by Traefik. This file lives in the dynamic folder.
 */
@Serializable
data class TraefikTlsConfig(
    @SerialName("tls") val tls: Tls
) {

    @Serializable
    data class Tls(
        @SerialName("certificates") val certificates: List<Certificate>
    ) {

        @Serializable
        data class Certificate(
            @SerialName("certFile") val certFile: String,
            @SerialName("keyFile") val keyFile: String
        )
    }
}