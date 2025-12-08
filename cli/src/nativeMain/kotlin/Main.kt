import app.SudoManager
import app.config.MainConfig
import app.dependencies.android_dns.Unbound
import app.dependencies.docker.DockerNetwork
import app.dependencies.openssl.OpensslHandler
import app.dependencies.postgres.Postgres18
import app.dependencies.reverse_proxy.TraefikManager
import app.dependencies.AppDependency
import app.hosts.HostsManager
import app.repository.ProjectRepository
import app.storage.isDevMode
import com.github.ajalt.clikt.command.main
import commands.MainCommand
import es.jvbabi.docker.kt.docker.DockerClient
import es.jvbabi.kfile.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import util.WARNING
import util.buildStyledString
import kotlin.test.assertTrue

expect val platformModule: Module

fun main(args: Array<String>) {
    runBlocking {
        startKoin koin@{
            modules(
                platformModule,
                module {
                    single<OpensslHandler> { OpensslHandler() }
                    single<MainConfig> { MainConfig() }
                    single<SudoManager> { SudoManager() }
                    single<HostsManager> { HostsManager(File("/etc/hosts")) }
                    single<DockerClient> { DockerClient() }
                    single<DockerNetwork> { DockerNetwork() }
                    singleOf(::Postgres18)
                    singleOf(::TraefikManager)
                    singleOf(::Unbound)

                    // Aggregate all AppDependencies for convenient injection as a list
                    single<List<AppDependency>> { listOf(get<TraefikManager>(), get<Unbound>(), get<Postgres18>()) }

                    single<ProjectRepository> { ProjectRepository() }
                }
            )

        }

        Application(this).run(args)
    }
}


class Application(
    private val coroutineScope: CoroutineScope
): KoinComponent {

    suspend fun run(args: Array<String>) {
        try {
            val isCompletion = args.getOrNull(0) == "completion"
            if (!isCompletion) {
                val openSslHandler by inject<OpensslHandler>()

                coroutineScope.launch { openSslHandler.initialize() }

                if (isDevMode) println(buildStyledString { yellow { +"$WARNING Running werkbank in development mode" } })

                assertTrue(openSslHandler.isOpensslAvailable.await())
            }

            inject<MainConfig>().value.updateConfig { it }

            MainCommand()
                .main(args)
        } finally {
            inject<DockerClient>().value.close()
        }
    }
}
