package app.werkbank

import app.certificates.CertificateManager
import app.certificates.LetsEncryptCertificateManager
import app.certificates.LocalCertificateManager
import app.queue.certificate.CertificateQueue
import app.werkbank.app.queue.request.RequestPersistenceQueue
import app.werkbank.app.cli.ImportCliBinaries
import app.werkbank.app.dns.CloudflareDnsManagerImpl
import app.werkbank.app.dns.DnsManager
import app.werkbank.app.dns.LocalHostsDnsManagerImpl
import app.werkbank.app.dns.local.SudoManager
import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.config.AppConfig
import app.werkbank.data.repository.CliBinaryRepository
import app.werkbank.data.repository.CliBinaryRepositoryImpl
import app.werkbank.database.DatabaseManager
import app.werkbank.util.AnchoredWallClock
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.util.logging.KtorSimpleLogger
import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.createOpenTelemetry
import io.opentelemetry.kotlin.error.SdkErrorSeverity
import io.opentelemetry.kotlin.tracing.Tracer
import io.opentelemetry.kotlin.tracing.export.batchSpanProcessor
import io.opentelemetry.kotlin.tracing.export.otlpHttpSpanExporter
import io.opentelemetry.kotlin.tracing.sampling.alwaysOn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.io.File

val APP_STORAGE_ROOT_QUALIFIER = named("storage-root")

fun Application.configureKoin(
    storageRoot: File
) {
    install(Koin) {
        slf4jLogger()
        modules(module {
            single(APP_STORAGE_ROOT_QUALIFIER) { storageRoot }
            single {
                val json = Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                }

                val configFile = storageRoot.resolve(File("config.json"))
                val config: AppConfig = json.decodeFromString(configFile.readText())
                config
            }

            single {
                val config: AppConfig = get()
                DatabaseManager(config.database.url)
            }

            single {
                HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json()
                    }
                }
            }

            single {
                val config: AppConfig = get()
                val logger = KtorSimpleLogger("OpenTelemetry")
                createOpenTelemetry(clock = AnchoredWallClock()) {
                    serviceName = config.otel.serviceName
                    // Without a handler the SDK swallows its own failures, so a collector that
                    // rejects every batch looks exactly like a server that produces no spans.
                    errorHandler { error ->
                        when (error.severity) {
                            SdkErrorSeverity.ERROR -> logger.error(error.toString())
                            SdkErrorSeverity.WARNING -> logger.warn(error.toString())
                            SdkErrorSeverity.INFO -> logger.info(error.toString())
                        }
                    }
                    // A tunnelled request carrying traceparent (e.g. from an instrumented frontend)
                    // continues that trace instead of starting an unrelated one.
                    propagator { composite(w3cTraceContext(), w3cBaggage()) }
                    tracerProvider {
                        // Not parent-based (the default): the server's own traces are the tool for
                        // debugging a tunnel, so a client that sends traceparent with sampled=0 must
                        // not be able to switch them off.
                        sampler { alwaysOn() }
                        export {
                            batchSpanProcessor(
                                otlpHttpSpanExporter(config.otel.endpoint)
                            )
                        }
                    }
                }
            }

            single<Tracer> {
                val openTelemetry: OpenTelemetry = get()
                openTelemetry.tracerProvider.getTracer(
                    name = "werkbank",
                    version = "0.0.1"
                )
            }

            single { SudoManager() }
            single<DnsManager> {
                val config: AppConfig = get()
                if (config.cloudflare != null && config.domainSuffix.endsWith(config.cloudflare.domain)) CloudflareDnsManagerImpl()
                else LocalHostsDnsManagerImpl(get())
            }
            single<CertificateManager> {
                runBlocking {
                    val config: AppConfig = get()
                    when (config.tls) {
                        is AppConfig.Tls.SelfSigned -> LocalCertificateManager()
                        is AppConfig.Tls.LetsEncrypt -> LetsEncryptCertificateManager()
                    }.also { it.init() }
                }
            }
            single { CertificateQueue() }
            single { RequestPersistenceQueue() }

            single { TunnelManager() }
            singleOf(::CliBinaryRepositoryImpl) bind CliBinaryRepository::class
            single { ImportCliBinaries() }
        })
    }
}
