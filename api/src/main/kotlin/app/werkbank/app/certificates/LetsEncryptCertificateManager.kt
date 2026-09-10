package app.certificates

import app.werkbank.app.dns.DnsManager
import app.werkbank.config.AppConfig
import app.werkbank.util.forEachAsync
import io.ktor.util.logging.*
import io.opentelemetry.kotlin.tracing.Span
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.shredzone.acme4j.Account
import org.shredzone.acme4j.AccountBuilder
import org.shredzone.acme4j.Session
import org.shredzone.acme4j.Status
import org.shredzone.acme4j.challenge.Dns01Challenge
import org.shredzone.acme4j.exception.AcmeRateLimitedException
import org.shredzone.acme4j.util.KeyPairUtils
import java.io.File
import java.net.URL
import java.security.KeyPair
import java.security.Security
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant
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
        val RATE_LIMIT_DELAY = 1.minutes
        val ORDER_POLL_DELAY = 10.seconds
    }

    override suspend fun init() {
        Security.addProvider(BouncyCastleProvider())

        val config = appConfig.tls as AppConfig.Tls.LetsEncrypt

        session = Session(when (config.mode) {
            AppConfig.Tls.LetsEncrypt.Mode.Production -> "acme://letsencrypt.org"
            AppConfig.Tls.LetsEncrypt.Mode.Staging -> "acme://letsencrypt.org/staging"
        })
        val keyPairFile = File(config.keypairPath)
        if (!keyPairFile.exists()) {
            logger.warn("No keypair found at ${keyPairFile.absolutePath}. Generating new keypair...")
            val generatedKeyPair = KeyPairUtils.createECKeyPair("secp256r1")
            KeyPairUtils.writeKeyPair(generatedKeyPair, keyPairFile.outputStream().bufferedWriter())
        }
        keyPair = KeyPairUtils.readKeyPair(keyPairFile.inputStream().bufferedReader())
        account = AccountBuilder()
            .addContact("mailto:${config.email}")
            .agreeToTermsOfService()
            .useKeyPair(keyPair)
            .create(session)

        accountUrl = account.location
    }

    override suspend fun requestCertificate(
        span: Span,
        domains: List<String>
    ): CertificateResult {
        val requestId = Uuid.random()

        span.setStringAttribute("certificate.domains", domains.joinToString())
        span.setStringAttribute("certificate.request_id", requestId.toString())

        val order = account.newOrder()
            .domains(domains)
            .create()

        span.addEvent("order.created")
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
                    span.addEvent("dns.challenge.created", attributes = {
                        setStringAttribute("challenge.domain", resourceName)
                    })
                    logger.info("$requestId: Created TXT record for $resourceName: $digest, waiting $DNS_DELAY before triggering challenge...")
                    delay(DNS_DELAY)
                    logger.info("$requestId: Triggering challenge for $resourceName...")
                    challenge.trigger()
                    span.addEvent("dns.challenge.triggered", attributes = {
                        setStringAttribute("challenge.domain", resourceName)
                    })

                    while (authorization.status == Status.PENDING || authorization.status == Status.PROCESSING) {
                        logger.info("$requestId: Authorization status is ${authorization.status}, waiting 10 seconds...")
                        authorization.fetch()
                        span.addEvent("dns.authorization.poll", attributes = {
                            setStringAttribute("challenge.domain", resourceName)
                            setStringAttribute("authorization.status", authorization.status.name)
                        })
                        delay(10.seconds)
                    }

                    val newStatus = authorization.status
                    span.addEvent("dns.authorization.result", attributes = {
                        setStringAttribute("challenge.domain", resourceName)
                        setStringAttribute("authorization.status", newStatus.name)
                    })
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
        span.addEvent("order.executed")
        logger.info("$requestId: Order executed successfully")
        while (order.status == Status.PENDING || order.status == Status.PROCESSING) {
            logger.info("$requestId: Order status is ${order.status}, waiting $ORDER_POLL_DELAY...")
            try {
                order.fetch()
                span.addEvent("order.poll", attributes = {
                    setStringAttribute("order.status", order.status.name)
                })
                delay(ORDER_POLL_DELAY)
            } catch (_: AcmeRateLimitedException) {
                logger.warn("$requestId: Rate limited, waiting $RATE_LIMIT_DELAY...")
                delay(RATE_LIMIT_DELAY)
                span.addEvent("rate-limited")
                continue
            }
        }

        if (order.status != Status.VALID) {
            logger.error("$requestId: Order status is ${order.status}")
            span.addEvent("certificate.error", attributes = {
                setStringAttribute("error", "Order status is ${order.status}")
            })
            return CertificateResult.Error("Order status is ${order.status}")
        }

        val certificate = order.certificate
        val certificateBytes = buildString {
            for (cert in certificate.certificateChain) {
                appendLine("-----BEGIN CERTIFICATE-----")

                val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
                    .encodeToString(cert.encoded)

                appendLine(base64)
                appendLine("-----END CERTIFICATE-----")
            }
        }.toByteArray()

        val privateKeyBytes = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")

            val encoded = Base64.getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(domainKeyPair.private.encoded)

            appendLine(encoded)
            appendLine("-----END PRIVATE KEY-----")
        }.toByteArray()


        span.addEvent("certificate.saved")

        val validUntil = certificate.certificate.notAfter.toInstant().toKotlinInstant()
        return CertificateResult.Success(
            privateKey = privateKeyBytes,
            certificate = certificateBytes,
            validUntil = validUntil
        )
    }
}