import app.config.MainConfig
import app.dependencies.openssl.OpensslHandler
import es.jvbabi.kfile.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.assertTrue

expect val platformModule: Module

fun main() {
    runBlocking {
        startKoin koin@{
            modules(
                platformModule,
                module {
                    single<OpensslHandler> { OpensslHandler() }
                }
            )

        }
        Application(this).run()
    }
}


class Application(
    private val coroutineScope: CoroutineScope
): KoinComponent {

    suspend fun run() {
        val openSslHandler by inject<OpensslHandler>()
        coroutineScope.launch { openSslHandler.initialize() }

        println("Running in ${File.getWorkingDirectory().absolutePath}")
        if (File.getWorkingDirectory().resolve("devmode").exists()) println("Dev mode")

        println("Openssl is required")
        assertTrue(openSslHandler.isOpensslAvailable.await())
        println("Openssl is available")

        println(MainConfig().getConfig())
        MainConfig().updateConfig { it }
    }
}
