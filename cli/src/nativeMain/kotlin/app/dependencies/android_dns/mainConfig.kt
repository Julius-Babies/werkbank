package app.dependencies.android_dns

import es.jvbabi.kfile.File
import io.ktor.utils.io.core.toByteArray
import org.kotlincrypto.hash.sha1.SHA1

private const val BASE_CONFIG = """
server:
    interface: 0.0.0.0
    port: 53
    interface: 0.0.0.0@853

    # Access control
    access-control: 127.0.0.0/8 allow
    access-control: 172.16.0.0/12 allow
    access-control: 0.0.0.0/0 allow

    # Performance settings
    num-threads: 1
    msg-buffer-size: 65552
    msg-cache-size: 4m
    rrset-cache-size: 4m
    cache-max-ttl: 86400

    # Security settings
    hide-identity: yes
    hide-version: yes
    private-address: 192.168.0.0/16
    private-address: 172.16.0.0/12
    private-address: 10.0.0.0/8

    # Logging configuration
    verbosity: 5
    use-syslog: no
    logfile: ""
    log-queries: yes
    log-replies: yes

    # Cache configuration
    do-not-query-localhost: no
    prefetch: yes
    prefetch-key: yes

    # DNSSEC configuration
    trust-anchor-file: "/usr/share/dnssec-root/trusted-key.key"
    val-permissive-mode: no
    val-clean-additional: yes
    LOCAL_ZONES


remote-control:
    control-enable: yes

forward-zone:
    name: "."
    forward-tls-upstream: no
    forward-addr: 1.1.1.1@53#cloudflare-dns.com
    forward-addr: 8.8.8.8@53#dns.google
"""

fun updateUnboundConfigIfNecessary(file: File, domains: List<String>) {
    val content = BASE_CONFIG
        .replace(
            "    LOCAL_ZONES", buildString {
                append("local-zone: \"werkbank.dev\" static\n")
                append("local-zone: \"werkbank.dev.\" static\n")
                domains.forEach { hosts ->
                    append("local-data: \"$hosts. IN A 10.0.2.2\"\n")
                }
            }.prependIndent("    ")
        )

    if (!file.exists()) {
        file.writeText(content)
        return
    }
    val currentContentHash = SHA1().apply { update(file.readText().toByteArray()) }.digest().contentHashCode()
    val newContentHash = SHA1().apply { update(content.toByteArray()) }.digest().contentHashCode()
    if (currentContentHash != newContentHash) {
        file.writeText(content)
    }
}