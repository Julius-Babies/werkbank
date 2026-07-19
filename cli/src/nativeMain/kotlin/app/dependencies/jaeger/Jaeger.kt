package app.dependencies.jaeger

import app.data.Project
import app.dependencies.AppDependency
import app.dependencies.ReverseProxyRecord
import app.dependencies.docker.DockerContainer
import app.dependencies.docker.DockerNetwork
import app.dependencies.docker.NetworkConfig
import app.hosts.HostsManager
import app.storage.isDevMode
import app.storage.storageRoot
import es.jvbabi.docker.kt.api.container.Container
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString
import kotlin.getValue

class Jaeger: AppDependency, KoinComponent {
    private val dockerNetwork by inject<DockerNetwork>()
    private val hostsManager by inject<HostsManager>()

    private val name = buildString {
        append("werkbank-")
        if (isDevMode) append("dev-")
        append("jaeger")
    }

    val jaegerRoot = storageRoot
        .resolve("jaeger")
        .resolve("data")

    val jaegerHostname = "jaeger.werkbank.studio"
    fun getContainer(): DockerContainer {
        return DockerContainer(
            image = "jaegertracing/all-in-one:1.76.0",
            name = this.name,
            ports = listOf(
                Container.PortBinding(4317, 4317, Container.PortBinding.Protocol.TCP),
                Container.PortBinding(4318, 4318, Container.PortBinding.Protocol.TCP),
                Container.PortBinding(14250, 14250, Container.PortBinding.Protocol.UDP),
                Container.PortBinding(14250, 14250, Container.PortBinding.Protocol.TCP),
                Container.PortBinding(14268, 14268, Container.PortBinding.Protocol.TCP),
                Container.PortBinding(9411, 9411, Container.PortBinding.Protocol.TCP),
            ),
            volumes = mapOf(
                Container.VolumeBind.Host(jaegerRoot.absolutePath) to "/badger/data",
            ),
            environment = mapOf(
                "COLLECTOR_ZIPKIN_HOST_PORT" to ":9411"
            ),
            networkConfigs = listOf(
                NetworkConfig(
                    network = dockerNetwork,
                    aliases = listOf(jaegerHostname)
                )
            )
        )
    }

    override val key: String = "jaeger"

    override suspend fun configure() {
        if (!jaegerRoot.exists()) jaegerRoot.mkdir(recursive = true)
        hostsManager.addHost(jaegerHostname)
    }

    override suspend fun provision() {
        if (getContainer().getState() == DockerContainer.State.NotExisting) getContainer().create()
    }

    override suspend fun managedContainers(): List<DockerContainer> = listOf(getContainer())

    override suspend fun start() {
        val containerName = getContainer().name
        println(buildStyledString { green { +"Starting Jaeger ($containerName)" } })
        getContainer().start(createIfNotExists = true)
    }

    override suspend fun stop() {
        val containerName = getContainer().name
        println(buildStyledString { blue { +"Stopping Jaeger ($containerName)" } })
        getContainer().stop()
    }

    override fun isRequiredFor(project: Project): Boolean =
        project.getConfig().dependencies?.jaeger ?: false

    override fun isAlwaysRequired(): Boolean = false

    override val webfacingDomains: List<String> = listOf(jaegerHostname)
    override val reverseProxyRecords: List<ReverseProxyRecord> = listOf(
        ReverseProxyRecord(
            domain = jaegerHostname,
            port = 16686,
            containerName = getContainer().name
        )
    )
}