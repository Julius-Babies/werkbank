package app.werkbank.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Werkbankfile(
    @SerialName("disallow_cloud") val disallowCloud: Boolean = false,
    @SerialName("project") val project: Project,
    @SerialName("containers") val containers: List<Container> = emptyList(),
    @SerialName("services") val services: List<Service> = emptyList(),
    @SerialName("http") val http: List<Http> = emptyList(),
    @SerialName("dependencies") val dependencies: Dependencies? = null,
    @SerialName("extra_certificate_directories") val extraCertificates: List<String> = emptyList(),
) {
    @Serializable
    data class Project(
        @SerialName("name") val name: String,
        @SerialName("id") val id: String,
        @SerialName("external_domain") val externalDomain: String? = null,
    )

    @Serializable
    data class Container(
        @SerialName("name") val name: String,
        @SerialName("image") val image: String,
        @SerialName("ports") val ports: List<String> = emptyList(),
        @SerialName("volumes") val volumes: List<String> = emptyList(),
        @SerialName("environment") val environment: Map<String, String> = emptyMap(),
        @SerialName("type") val type: Type
    ) {
        @Serializable
        enum class Type {
            @SerialName("dependency") Dependency,
            @SerialName("service") Service
        }
    }

    @Serializable
    data class Service(
        @SerialName("name") val name: String,
        @SerialName("modes") val modes: Modes,
    ) {
        @Serializable
        data class Modes(
            @SerialName("local") val local: Local? = null,
            @SerialName("docker") val docker: Docker? = null,
        ) {
            @Serializable
            data class Local(
                @SerialName("port") val port: Int
            )

            @Serializable
            data class Docker(
                @SerialName("container") val container: String,
                @SerialName("port") val port: Int
            )
        }
    }

    @Serializable
    data class Http(
        @SerialName("path_prefixes") val pathPrefixes: List<String> = listOf("/"),
        @SerialName("domains") val domains: List<String> = emptyList(),
        @SerialName("external_domains") val externalDomains: List<String> = emptyList(),
        @SerialName("target_service") val targetService: String,
        @SerialName("priority") val priority: Int? = null,
        @SerialName("description") val description: String? = null,
    )

    @Serializable
    data class Dependencies(
        @SerialName("postgres") val postgres: Postgres? = null,
        @SerialName("mongodb") val mongodb: MongoDb? = null,
        @SerialName("rabbitmq") val rabbitmq: RabbitMq? = null,
        @SerialName("androiddns") val androidDns: Boolean = false,
        @SerialName("jaeger") val jaeger: Boolean = false,
        @SerialName("keycloak") val keycloak: Boolean = false,
    ) {

        @Serializable
        data class Postgres(
            @SerialName("18") val postgres18: Postgres18? = null
        ) {
            @Serializable
            data class Postgres18(
                @SerialName("databases") val databases: List<String> = emptyList()
            )
        }

        @Serializable
        data class MongoDb(
            @SerialName("databases") val databases: List<String> = emptyList()
        )

        @Serializable
        data class RabbitMq(
            @SerialName("vhosts") val vhosts: List<String> = emptyList()
        )
    }
}
