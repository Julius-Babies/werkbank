package commands.setup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Werkbankfile(
    @SerialName("project") val project: Project,
    @SerialName("containers") val containers: List<Container> = emptyList(),
    @SerialName("services") val services: List<Service> = emptyList(),
) {
    @Serializable
    data class Project(
        @SerialName("name") val name: String,
        @SerialName("id") val id: String,
    )

    @Serializable
    data class Container(
        @SerialName("name") val name: String,
        @SerialName("image") val image: String,
        @SerialName("ports") val ports: List<String> = emptyList(),
        @SerialName("volumes") val volumes: List<String> = emptyList(),
        @SerialName("environment") val environment: Map<String, String> = emptyMap(),
        @SerialName("type") val type: Type,
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
        @SerialName("path_prefixes") val pathPrefixes: List<String> = listOf("/"),
        @SerialName("domains") val domains: List<String> = emptyList(),
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
}