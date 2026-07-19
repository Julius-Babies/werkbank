package app.dependencies.reverse_proxy

/**
 * Provider-neutral description of everything a reverse proxy has to route.
 *
 * A [ReverseProxy] implementation turns this into its own configuration format
 * (Traefik YAML, a Caddyfile, nginx.conf, ...). Nothing in here is specific to a
 * concrete proxy implementation, so swapping the provider only means writing a new
 * [ReverseProxy] that renders this model.
 */
data class ReverseProxyConfiguration(
    val groups: List<Group>,
    /**
     * Domains that must resolve to the local machine. The provider is expected to
     * make sure these end up in the hosts file. User-facing project domains are not
     * listed here; they are registered when a project is imported.
     */
    val managedHosts: List<String> = emptyList(),
) {
    /**
     * A single backend ([target]) reachable through one or more [routes].
     *
     * @param name Stable, unique identifier for this group. Providers may use it to
     *   name config files or internal services, so it must be filesystem safe.
     * @param descriptions Free-form notes that a provider may render as comments.
     */
    data class Group(
        val name: String,
        val target: Target,
        val routes: List<Route>,
        val descriptions: List<String> = emptyList(),
    )

    /**
     * A matching rule: traffic matches when any of [hosts] matches and, when
     * [pathPrefixes] is non-empty, any of the prefixes matches as well. An empty
     * [pathPrefixes] matches every path.
     */
    data class Route(
        val hosts: List<HostMatch>,
        val pathPrefixes: List<String> = emptyList(),
        val priority: Int? = null,
    )

    /** Where matched traffic is forwarded to. */
    sealed interface Target {
        /** A service running on the host machine, reachable on [port]. */
        data class Host(val port: Int) : Target

        /** A docker container [hostname] reachable on [port]. */
        data class DockerContainer(val hostname: String, val port: Int) : Target
    }

    /** How an incoming host header is matched. */
    sealed interface HostMatch {
        /** Matches exactly [domain]. */
        data class Exact(val domain: String) : HostMatch

        /** Matches exactly one extra label in front of [base] (e.g. `*.example.com`). */
        data class SubdomainWildcard(val base: String) : HostMatch

        /** Matches one or more extra labels in front of [base] (e.g. `**.example.com`). */
        data class DeepWildcard(val base: String) : HostMatch
    }
}
