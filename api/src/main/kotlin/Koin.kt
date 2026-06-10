package app.werkbank

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
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.io.File

fun Application.configureKoin(
    storageRoot: File
) {
    install(Koin) {
        slf4jLogger()
        modules(module {
            single {
                val json = Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                }

                val configFile = storageRoot.resolve(File("./data/config.json"))
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
        })
    }
}
