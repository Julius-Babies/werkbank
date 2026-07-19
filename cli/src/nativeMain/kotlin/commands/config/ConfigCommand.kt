package commands.config

import app.config.MainConfig
import app.config.WerkbankConfig
import app.dependencies.AppDependency
import app.dependencies.DependencyOrchestrator
import app.dependencies.docker.DockerContainer
import app.dependencies.keycloak.Keycloak
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.subcommands
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import util.buildStyledString

class ConfigCommand : SuspendingCliktCommand("config"), KoinComponent {
    private val mainConfig by inject<MainConfig>()
    private val dependencies by inject<List<AppDependency>>(named("Dependencies"))
    private val orchestrator by inject<DependencyOrchestrator>()
    override suspend fun run() {

    }

    private suspend fun replaceKeycloakContainer() {
        val keycloak = dependencies.filterIsInstance<Keycloak>().firstOrNull() ?: return
        // Nothing to do if Keycloak was never set up. Otherwise the generic update
        // path re-pulls the (new) image and recreates the container.
        if (keycloak.getContainer().getState() == DockerContainer.State.NotExisting) return
        orchestrator.update(listOf(keycloak.key))
    }

    init {
        this.subcommands(
            BooleanCommandConfig(
                key = "android-dns.enabled",
                set = { value ->
                    mainConfig.updateConfig {
                        it.copy(
                            androidDns = it.androidDns.copy(
                                enabled = value
                            )
                        )
                    }
                },
                getValue = { it.androidDns.enabled }
            ),

            StringCommandConfig(
                key = "keycloak.image",
                set = { value ->
                    mainConfig.updateConfig {
                        it.copy(
                            keycloak = it.keycloak.copy(
                                image = value
                            )
                        )
                    }
                    replaceKeycloakContainer()
                    echo(buildStyledString {
                        yellow {
                            +"Keycloak image was changed. Werkbank uses ${WerkbankConfig.KeycloakConfig.KEYCLOAK_DEFAULT_IMAGE} by default. Make sure, your image supports all the commands that Werkbank expectes with this version."
                        }
                    })
                },
                reset = {
                    mainConfig.updateConfig {
                        it.copy(
                            keycloak = it.keycloak.copy(
                                image = WerkbankConfig.KeycloakConfig.KEYCLOAK_DEFAULT_IMAGE,
                            )
                        )
                    }
                },
                getValue = { it.keycloak.image }
            )
        )
    }
}