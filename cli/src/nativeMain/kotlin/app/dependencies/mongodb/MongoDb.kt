package app.dependencies.mongodb

import app.data.Project
import app.dependencies.AppDependency
import app.dependencies.ReverseProxyRecord
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.hosts.HostsManager
import app.storage.isDevMode
import app.storage.storageRoot
import es.jvbabi.docker.kt.api.container.NetworkConfig
import es.jvbabi.docker.kt.api.container.PortBinding
import es.jvbabi.docker.kt.api.container.VolumeBind
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.getValue

class MongoDb: AppDependency, KoinComponent {
    private val hostsManager by inject<HostsManager>()
    private val dockerNetwork by inject<DockerNetwork>()

    private val mongoRoot = storageRoot
        .resolve("mongodb")
        .resolve("data")


    val mongoDatabaseHostname = "mongodb.werkbank.studio"
    val mongoDatabaseContainer = DockerContainer(
        image = "mongo:8-noble",
        name = buildString {
            append("werkbank-")
            if (isDevMode) append("dev-")
            append("mongodb")
        },
        ports = listOf(
            PortBinding(27017, 27017, PortBinding.Protocol.TCP)
        ),
        volumes = mapOf(
            VolumeBind.Host(mongoRoot.absolutePath) to "/data/db"
        ),
        environment = mapOf(
            "MONGO_INITDB_ROOT_USERNAME" to "werkbank",
            "MONGO_INITDB_ROOT_PASSWORD" to "werkbank"
        ),
        networkConfigs = listOf(NetworkConfig(
            networkId = runBlocking { dockerNetwork.getId()!! },
            aliases = listOf(mongoDatabaseHostname)
        ))
    )

    val mongoExpressDomain = "mongo-express.werkbank.studio"
    val mongoExpressContainer = DockerContainer(
        image = "mongo-express:1.0.2-20-alpine3.19",
        name = buildString {
            append("werkbank-")
            if (isDevMode) append("dev-")
            append("mongo-express")
        },
        ports = emptyList(),
        environment = mapOf(
            "ME_CONFIG_MONGODB_ENABLE_ADMIN" to "true",
            "ME_CONFIG_MONGODB_ADMINUSERNAME" to "werkbank",
            "ME_CONFIG_MONGODB_ADMINPASSWORD" to "werkbank",
            "ME_CONFIG_BASICAUTH" to "false",
            "ME_CONFIG_MONGODB_SERVER" to mongoDatabaseHostname
        ),
        volumes = emptyMap(),
        networkConfigs = listOf(NetworkConfig(
            networkId = runBlocking { dockerNetwork.getId()!! },
            aliases = listOf(mongoExpressDomain)
        ))
    )

    override val key: String = "mongodb"

    override suspend fun initialize() {
        if (!mongoRoot.exists()) mongoRoot.mkdir(recursive = true)
        hostsManager.addHost(mongoDatabaseHostname)
        hostsManager.addHost(mongoExpressDomain)
        if (mongoDatabaseContainer.getState() == DockerContainer.State.NotExisting) mongoDatabaseContainer.create()
        if (mongoExpressContainer.getState() == DockerContainer.State.NotExisting) mongoExpressContainer.create()
    }

    override suspend fun start() {
        println(buildStyledString { green { +"Starting Mongo DB (${mongoDatabaseContainer.name})" } })
        mongoDatabaseContainer.start(createIfNotExists = true)
        println(buildStyledString { green { +"Starting Mongo Express (${mongoExpressContainer.name})" } })
        mongoExpressContainer.start(createIfNotExists = true)
    }

    override suspend fun stop() {
        println(buildStyledString { blue { +"Stopping Mongo DB (${mongoDatabaseContainer.name})" } })
        mongoDatabaseContainer.stop()
        println(buildStyledString { blue { +"Stopping Mongo Express (${mongoExpressContainer.name})" } })
        mongoExpressContainer.stop()
    }

    override val reverseProxyRecords: List<ReverseProxyRecord> = listOf(
        ReverseProxyRecord(
            domain = mongoExpressDomain,
            port = 8081,
            containerName = mongoExpressContainer.name
        )
    )

    override val webfacingDomains: List<String> = listOf(mongoExpressDomain)

    override fun isRequiredFor(project: Project): Boolean {
        return true
    }
}