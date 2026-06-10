package app.werkbank

import app.werkbank.app.certificates.CertificateManager
import app.werkbank.app.certificates.LocalCertificateGenerator
import app.werkbank.app.dns.CloudflareDnsManagerImpl
import app.werkbank.app.dns.DnsManager
import app.werkbank.app.dns.LocalHostsDnsManagerImpl
import app.werkbank.app.dns.local.SudoManager
import app.werkbank.config.AppConfig
import app.werkbank.database.DatabaseManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
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

            single { SudoManager() }
            single<DnsManager> {
                val config: AppConfig = get()
                if (config.cloudflare != null) CloudflareDnsManagerImpl()
                else LocalHostsDnsManagerImpl(get())
            }
            single<CertificateManager> {
                runBlocking {
                    val config: AppConfig = get()
                    when (config.tls) {
                        is AppConfig.Tls.SelfSigned -> LocalCertificateGenerator().also { it.init() }
                    }
                }
            }
        })
    }
}
