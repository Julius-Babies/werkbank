package app.certificates

import app.werkbank.config.AppConfig
import app.werkbank.database.Certificate
import app.werkbank.database.Certificates
import app.werkbank.database.DatabaseManager
import app.werkbank.database.User
import app.werkbank.database.Users
import io.ktor.util.logging.KtorSimpleLogger
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

class ServerKeyManager : X509ExtendedKeyManager(), KoinComponent {

    private val db by inject<DatabaseManager>()
    private val appConfig by inject<AppConfig>()
    private val cache = ConcurrentHashMap<String, CertKeyPair>()
    private val logger = KtorSimpleLogger("ServerKeyManager")

    private data class CertKeyPair(val cert: X509Certificate, val key: PrivateKey)

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal?>?
    ): Array<out String?>? {
        error("Not supported")
    }

    override fun chooseClientAlias(
        keyType: Array<out String?>?,
        issuers: Array<out Principal?>?,
        socket: Socket?
    ): String? {
        error("Not supported")
    }

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal?>?
    ): Array<out String?>? {
        error("Not supported")
    }

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal?>?,
        socket: Socket?
    ): String? {
        error("Not supported")
    }

    override fun getCertificateChain(alias: String?): Array<out X509Certificate?>? {
        val username = alias ?: return null
        return resolve(username)?.let { arrayOf(it.cert) }
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        val username = alias ?: return null
        return resolve(username)?.key
    }

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal?>?,
        engine: SSLEngine?
    ): String? {
        val session = engine?.handshakeSession as? ExtendedSSLSession ?: return null

        val sniName = session.requestedServerNames
            .filterIsInstance<SNIHostName>()
            .firstOrNull()
            ?.asciiName
            ?: return null

        val suffix = ".${appConfig.domainSuffix}"
        if (!sniName.endsWith(suffix)) return null

        return sniName.removeSuffix(suffix).split(".").lastOrNull()
    }

    private fun resolve(username: String): CertKeyPair? {
        cache[username]?.let { return it }

        logger.trace("resolve: looking up $username")
        val pair = try {
            db.queryBlocking {
                val user = User.find { Users.username.lowerCase() eq username.lowercase() }
                    .firstOrNull()
                if (user == null) {
                    logger.warn("resolve: user $username not found in DB")
                    return@queryBlocking null
                }
                logger.trace("resolve: found user {}", user.id)

                val certRecord = Certificate.find { Certificates.user eq user.id }
                    .orderBy(Certificates.createdAt to SortOrder.DESC)
                    .firstOrNull()
                if (certRecord == null) {
                    logger.warn("resolve: no certificate found for user $username")
                    return@queryBlocking null
                }
                logger.trace("resolve: found cert record {}", certRecord.id)

                val cert = parseCertificate(certRecord.certificate.bytes)
                if (cert == null) {
                    logger.warn("resolve: failed to parse certificate for $username")
                    return@queryBlocking null
                }

                val key = parsePrivateKey(certRecord.privateKey.bytes)
                if (key == null) {
                    logger.warn("resolve: failed to parse private key for $username")
                    return@queryBlocking null
                }

                CertKeyPair(cert, key)
            }
        } catch (e: Exception) {
            logger.error("resolve: exception for $username: ${e.message}")
            null
        }

        if (pair != null) {
            logger.trace("resolve: cached cert+key for $username")
            cache[username] = pair
        }
        return pair
    }

    private fun parseCertificate(pemBytes: ByteArray): X509Certificate? = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pemBytes)) as X509Certificate
    } catch (_: Exception) {
        null
    }

    private fun parsePrivateKey(pemBytes: ByteArray): PrivateKey? = try {
        val pem = pemBytes.decodeToString()
        val base64 = pem.lineSequence().filter { !it.startsWith("-----") }.joinToString("")
        val encoded = Base64.getDecoder().decode(base64)
        val spec = PKCS8EncodedKeySpec(encoded)

        try {
            KeyFactory.getInstance("RSA").generatePrivate(spec)
        } catch (_: Exception) {
            KeyFactory.getInstance("EC").generatePrivate(spec)
        }
    } catch (_: Exception) {
        null
    }
}