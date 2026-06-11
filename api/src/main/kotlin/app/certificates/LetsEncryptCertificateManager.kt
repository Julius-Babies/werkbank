package app.werkbank.app.certificates

import app.werkbank.app.dns.DnsManager
import app.werkbank.config.AppConfig
import app.werkbank.util.forEachAsync
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.shredzone.acme4j.Account
import org.shredzone.acme4j.AccountBuilder
import org.shredzone.acme4j.Session
import org.shredzone.acme4j.Status
import org.shredzone.acme4j.challenge.Dns01Challenge
import org.shredzone.acme4j.util.KeyPairUtils
import java.io.File
import java.io.FileWriter
import java.net.URL
import java.security.KeyPair
import java.security.Security
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid


class LetsEncryptCertificateManager : CertificateManager, KoinComponent {

    private val appConfig by inject<AppConfig>()
    private val dnsManager by inject<DnsManager>()
    private val logger = KtorSimpleLogger("LetsEncryptCertificateManager")
    private lateinit var keyPair: KeyPair
    private lateinit var account: Account
    private lateinit var session: Session
    private lateinit var accountUrl: URL

    companion object {
        val DNS_DELAY = 2.minutes
    }

    override suspend fun init() {
        Security.addProvider(BouncyCastleProvider())
        session = Session("acme://letsencrypt.org/staging")
        val keyPairFile = File((appConfig.tls as AppConfig.Tls.LetsEncrypt).keypairPath)
        if (!keyPairFile.exists()) {
            logger.warn("No keypair found at ${keyPairFile.absolutePath}. Generating new keypair...")
            val generatedKeyPair = KeyPairUtils.createECKeyPair("secp256r1")
            KeyPairUtils.writeKeyPair(generatedKeyPair, keyPairFile.outputStream().bufferedWriter())
        }
        keyPair = KeyPairUtils.readKeyPair(keyPairFile.inputStream().bufferedReader())
        account = AccountBuilder()
            .addContact("mailto:${(appConfig.tls as AppConfig.Tls.LetsEncrypt).email}")
            .agreeToTermsOfService()
            .useKeyPair(keyPair)
            .create(session)

        accountUrl = account.location
    }

    override suspend fun requestCertificate(domains: List<String>, targetCertFile: File, targetKeyFile: File) {
        val requestId = Uuid.random()
        val login = session.login(accountUrl, keyPair)
        val order = account.newOrder()
            .domains(domains)
            .create()

        logger.info("$requestId: Created order for ${domains.joinToString()}")
        coroutineScope {
            order
                .authorizations
                .filter { it.status == Status.PENDING }
                .forEachAsync { authorization ->
                    val challenge = authorization.findChallenge(Dns01Challenge::class.java).get()
                    val resourceName = challenge.getRRName(authorization.identifier)
                    val digest = challenge.digest
                    logger.info("$requestId: Found challenge for $resourceName: $digest")
                    dnsManager.createTxtRecord(
                        domain = resourceName,
                        content = digest
                    )
                    logger.info("$requestId: Created TXT record for $resourceName: $digest, waiting $DNS_DELAY before triggering challenge...")
                    delay(DNS_DELAY)
                    logger.info("$requestId: Triggering challenge for $resourceName...")
                    challenge.trigger()

                    while (authorization.status == Status.PENDING || authorization.status == Status.PROCESSING) {
                        logger.info("$requestId: Authorization status is ${authorization.status}, waiting 10 seconds...")
                        authorization.fetch()
                        delay(10.seconds)
                    }

                    val newStatus = authorization.status
                    if (newStatus == Status.VALID) {
                        return@forEachAsync
                    } else {
                        logger.error("$requestId: Authorization failed with status $newStatus")
                        this.cancel("Authorization failed with status $newStatus")
                    }
                }
        }

        val domainKeyPair = KeyPairUtils.createECKeyPair("secp256r1")
        order.execute(domainKeyPair, { csr ->
            csr.setCountry("DE")
            csr.setOrganization("Werkbank Cloud")
        })
        logger.info("$requestId: Order executed successfully")
        while (order.status == Status.PENDING || order.status == Status.PROCESSING) {
            logger.info("$requestId: Order status is ${order.status}, waiting 10 seconds...")
            order.fetch()
            delay(10.seconds)
        }

        if (order.status != Status.VALID) {
            logger.error("$requestId: Order status is ${order.status}")
            return
        }

        val certificate = order.certificate
        // write chain
        withContext(Dispatchers.IO) {
            FileWriter(targetCertFile).use { writer ->
                for (cert in certificate.certificateChain) {
                    writer.write("-----BEGIN CERTIFICATE-----\n")

                    val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
                        .encodeToString(cert.encoded)

                    writer.write(base64)
                    writer.write("\n-----END CERTIFICATE-----\n")
                }
            }

            FileWriter(targetKeyFile).use { writer ->
                writer.write("-----BEGIN PRIVATE KEY-----\n")

                val encoded = Base64.getMimeEncoder(64, "\n".toByteArray())
                    .encodeToString(domainKeyPair.private.encoded)

                writer.write(encoded)
                writer.write("\n-----END PRIVATE KEY-----\n")
            }
        }
    }
}