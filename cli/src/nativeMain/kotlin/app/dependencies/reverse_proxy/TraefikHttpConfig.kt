package app.dependencies.reverse_proxy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraefikHttpConfig(
    val http: Http
) {
    @Serializable
    data class Http(
        val routers: Map<String, Router> = emptyMap(),
        val services: Map<String, Service> = emptyMap(),
    ) {
        @Serializable
        data class Router(
            val rule: String,
            @SerialName("entryPoints")
            val entryPoints: List<String> = listOf("websecure"),
            val service: String,
            val tls: Tls = Tls(),
            val priority: Int? = null,
        ) {
            @Serializable
            data class Tls(
                val certResolver: String? = null,
            )
        }

        @Serializable
        data class Service(
            val loadBalancer: LoadBalancer,
        ) {
            @Serializable
            data class LoadBalancer(
                val passHostHeader: Boolean = true,
                val servers: List<Server>,
            ) {
                @Serializable
                data class Server(
                    val url: String,
                )
            }
        }
    }
}
