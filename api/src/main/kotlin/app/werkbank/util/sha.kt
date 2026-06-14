package app.werkbank.util

import org.kotlincrypto.hash.sha2.SHA256

fun String.sha256(): String {
    val digest = SHA256()
    digest.update(this.toByteArray(Charsets.UTF_8))
    return digest.digest().toHexString()
}