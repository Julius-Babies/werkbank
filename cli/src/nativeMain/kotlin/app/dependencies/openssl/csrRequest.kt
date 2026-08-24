package app.dependencies.openssl

/**
 * Config for the self-signed root CA. [domains] are written as subject alternative names.
 * They have no effect on TLS validation (hostname matching happens on the leaf certificate),
 * but allow werkbank to detect a root CA that was created for a different set of base domains.
 */
fun csrRequestConfigFileContent(cn: String, domains: List<String>) = buildString {
    appendLine(
        """
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = $cn
C = DE
ST = Saxony
L = Dresden
O = Werkbank

[v3_req]
basicConstraints = critical,CA:TRUE
subjectAltName = @alt_names

[alt_names]
""".trimStart()
    )
    domains.forEachIndexed { index, domain ->
        appendLine("DNS.${index + 1} = $domain")
    }
}
