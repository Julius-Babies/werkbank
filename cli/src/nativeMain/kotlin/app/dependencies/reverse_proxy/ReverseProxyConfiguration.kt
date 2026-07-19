package app.dependencies.reverse_proxy

data class ReverseProxyConfiguration(
    val groups: List<Group>
) {
    data class Group(
        val name: String,
        val routes: List<Route>,
    )

    data class Route(
        val hosts: List<String>,
        val pathPrefixes: List<String>,
        val target: Target,
    ) {
        sealed class Target {
            data class Host(val port: Int): Target()
            data class DockerContainer(val hostname: String, val port: Int): Target()
        }
    }
}
