package app.werkbank

import app.certificates.CertificateManager
import app.certificates.LetsEncryptCertificateManager
import app.certificates.LocalCertificateManager
import app.queue.certificate.CertificateQueue
import app.werkbank.app.dns.CloudflareDnsManagerImpl
import app.werkbank.app.dns.DnsManager
import app.werkbank.app.dns.LocalHostsDnsManagerImpl
import app.werkbank.app.dns.local.SudoManager
import app.werkbank.app.tunnel.TunnelManager
import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.createOpenTelemetry
import io.opentelemetry.kotlin.tracing.Tracer
import io.opentelemetry.kotlin.tracing.export.batchSpanProcessor
import io.opentelemetry.kotlin.tracing.export.otlpHttpSpanExporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
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
                createOpenTelemetry {
                    serviceName = config.otel.serviceName
                    tracerProvider {
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

            single { TunnelManager() }
        })
    }
}
