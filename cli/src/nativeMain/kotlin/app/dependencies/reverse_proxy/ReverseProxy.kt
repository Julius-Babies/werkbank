package app.dependencies.reverse_proxy

import app.dependencies.AppDependency

/**
 * A reverse proxy dependency (Traefik, Caddy, nginx, ...).
 *
 * The routing itself is described in a provider-neutral [ReverseProxyConfiguration]
 * that is produced by [ReverseProxyConfigurationResolver]. An implementation only has
 * to render that model in [apply] and provide the usual [AppDependency] lifecycle.
 */
interface ReverseProxy : AppDependency {
    /**
     * Renders [config] into the provider's own configuration format and applies it.
     * Must be idempotent and must not require any container to be running.
     */
    suspend fun apply(config: ReverseProxyConfiguration)
}
