package app.dependencies.keycloak

import app.config.MainConfig
import app.data.Project
import app.dependencies.AppDependency
import app.dependencies.ReverseProxyRecord
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.dependencies.docker.NetworkConfig
import app.dependencies.postgres.Postgres18
import app.hosts.HostsManager
import app.storage.isDevMode
import app.storage.storageRoot
import es.jvbabi.docker.kt.api.container.Container
import es.jvbabi.docker.kt.docker.DockerClient
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.time.Duration.Companion.seconds

class Keycloak : AppDependency, KoinComponent {
    override val key: String = "keycloak"

    private val dockerNetwork by inject<DockerNetwork>()
    private val dockerClient by inject<DockerClient>()
    private val hostsManager by inject<HostsManager>()
    private val mainConfig by inject<MainConfig>()
    private val postgres18 by inject<Postgres18>()

    private val keycloakRoot = storageRoot
        .resolve("keycloak")
        .resolve("data")

    val keycloakHostname = "keycloak.werkbank.studio"

    private fun getImage(): String = mainConfig.getConfig().keycloak.image

    fun getContainer(): DockerContainer {
        return DockerContainer(
            image = getImage(),
            name = buildString {
                append("werkbank-")
                if (isDevMode) append("dev-")
                append("keycloak")
            },
            ports = emptyList(),
            volumes = mapOf(
                Container.VolumeBind.Host(keycloakRoot.absolutePath) to "/opt/keycloak/data"
            ),
            healthcheck = Container.Healthcheck(
                test = listOf("CMD-SHELL", "/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user werkbank --password werkbank > /dev/null 2>&1"),
                interval = 5.seconds,
                timeout = 5.seconds,
                startPeriod = 10.seconds,
            ),
            environment = mapOf(
                "KC_BOOTSTRAP_ADMIN_USERNAME" to "werkbank",
                "KC_BOOTSTRAP_ADMIN_PASSWORD" to "werkbank",
                "KC_HTTP_PORT" to "8080",
                "KC_HTTP_ENABLED" to "true",
                "KC_PROXY_HEADERS" to "xforwarded",
                "KC_HOSTNAME" to "keycloak.werkbank.studio",
                "KC_STRICT_HTTPS" to "true",
                "KC_DB" to "postgres",
                "KC_DB_URL" to "jdbc:postgresql://postgres18.werkbank.studio:5432/keycloak",
                "KC_DB_USERNAME" to "werkbank",
                "KC_DB_PASSWORD" to "werkbank",
            ),
            cmd = listOf("start-dev"),
            networkConfigs = listOf(
                NetworkConfig(
                    network = dockerNetwork,
                    aliases = listOf(keycloakHostname)
                )
            )
        )
    }

    override val dependsOn: List<String> = listOf("postgres18")

    override suspend fun configure() {
        if (!keycloakRoot.exists()) keycloakRoot.mkdir(recursive = true)
        hostsManager.addHost(keycloakHostname)
    }

    override suspend fun provision() {
        // Keycloak needs its database to exist (and Postgres to be running) before
        // the container boots. Bringing Postgres up here is transitional: Phase 2's
        // orchestrator will guarantee Postgres via `dependsOn` and remove this.
        postgres18.configure()
        postgres18.provision()
        postgres18.start()
        postgres18.ensureReady()
        ensureKeycloakDatabase()
        if (getContainer().getState() == DockerContainer.State.NotExisting) getContainer().create()
    }

    private suspend fun ensureKeycloakDatabase() {
        val postgresContainer = postgres18.container
        postgresContainer.withRunning {
            val result = dockerClient.containers.runCommand(
                containerId = postgresContainer.getId()!!,
                environment = mapOf("PGPASSWORD" to "werkbank"),
                command = listOf("psql", "-U", "werkbank", "-t", "-A", "-c",
                    "SELECT 1 FROM pg_database WHERE datname = 'keycloak'")
            )
            if (result.output.trim() != "1") {
                dockerClient.containers.runCommand(
                    containerId = postgresContainer.getId()!!,
                    environment = mapOf("PGPASSWORD" to "werkbank"),
                    command = listOf("createdb", "-U", "werkbank", "keycloak")
                )
            }
        }
    }

    override suspend fun start() {
        val containerName = getContainer().name
        println(buildStyledString { green { +"Starting Keycloak ($containerName)" } })
        getContainer().start(createIfNotExists = true, rebuildIfNotMatching = true)
    }

    override suspend fun stop() {
        val containerName = getContainer().name
        println(buildStyledString { blue { +"Stopping Keycloak ($containerName)" } })
        getContainer().stop()
    }

    override fun isRequiredFor(project: Project): Boolean =
        project.getConfig().dependencies?.keycloak ?: false

    override fun isAlwaysRequired(): Boolean = false

    override val webfacingDomains: List<String> = listOf(keycloakHostname)
    override val reverseProxyRecords: List<ReverseProxyRecord> = listOf(
        ReverseProxyRecord(
            domain = keycloakHostname,
            port = 8080,
            containerName = getContainer().name
        )
    )

    suspend fun waitUntilReady() {
        val container = getContainer()
        require(container.getState() == DockerContainer.State.Running)
        while (true) {
            val result = dockerClient.containers.runCommand(
                containerId = container.getId()!!,
                command = listOf(
                    "/opt/keycloak/bin/kcadm.sh", "config", "credentials",
                    "--server", "http://localhost:8080",
                    "--realm", "master",
                    "--user", "werkbank",
                    "--password", "werkbank"
                )
            )
            if (result.exitCode == 0) break
            delay(2.seconds)
        }
    }

    suspend fun ensureRealm(projectId: String, projectName: String) {
        println(buildStyledString { blue { +"Creating Keycloak realm '$projectId' ($projectName)" } })
        getContainer().withRunning(requireHealthy = true) {
            waitUntilReady()

            val existingCheck = dockerClient.containers.runCommand(
                containerId = it.getId()!!,
                command = listOf(
                    "/opt/keycloak/bin/kcadm.sh", "get", "realms/$projectId"
                )
            )
            if (existingCheck.exitCode == 0) {
                println(buildStyledString { yellow { +"Realm '$projectId' already exists" } })
                return@withRunning
            }

            val createResult = dockerClient.containers.runCommand(
                containerId = it.getId()!!,
                command = listOf(
                    "/opt/keycloak/bin/kcadm.sh", "create", "realms",
                    "-s", "realm=$projectId",
                    "-s", "displayName=$projectName",
                    "-s", "enabled=true"
                )
            )
            require(createResult.exitCode == 0) {
                "Failed to create Keycloak realm '$projectId': ${createResult.output}"
            }
            println(buildStyledString { green { +"Keycloak realm '$projectId' created" } })
        }
    }
}
