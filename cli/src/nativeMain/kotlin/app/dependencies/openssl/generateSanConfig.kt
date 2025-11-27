package app.dependencies.openssl

fun generateSanConfig(alternativeNames: List<String>) = buildString {
    appendLine("authorityKeyIdentifier=keyid,issuer")
    appendLine("basicConstraints=CA:FALSE")
    appendLine("keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment")
    appendLine("subjectAltName = @alt_names")
    appendLine("")
    appendLine("[alt_names]")
    alternativeNames.forEachIndexed { index, name ->
        appendLine("DNS.${index + 1} = $name")
    }
}