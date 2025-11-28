package app.dependencies.reverse_proxy

import es.jvbabi.kfile.File
import io.ktor.utils.io.core.toByteArray
import org.kotlincrypto.hash.sha1.SHA1

private val dashboardService = """
http:
  routers:
    dashboard-api-router:
      rule: "Host(`traefik.werkbank.dev`) && PathPrefix(`/api`)"
      entryPoints:
        - websecure
      service: api@internal
      middlewares:
        - dashboard_stripprefix@internal
      tls: true
    dashboard-router:
      rule: "Host(`traefik.werkbank.dev`) && PathPrefix(`/`)"
      entryPoints:
        - websecure
      service: dashboard@internal
      middlewares:
        - dashboard_stripprefix@internal
      tls: true
""".trimIndent()

fun updateDashboardServiceIfNecessary(file: File) {
    val currentContentHash = SHA1().apply { update(file.readText().toByteArray()) }.digest().contentHashCode()
    val newContentHash = SHA1().apply { update(dashboardService.toByteArray()) }.digest().contentHashCode()
    if (currentContentHash != newContentHash) file.writeText(dashboardService)
}