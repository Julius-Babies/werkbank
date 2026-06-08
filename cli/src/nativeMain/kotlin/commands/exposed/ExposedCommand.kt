package commands.exposed

import app.dependencies.android_dns.Unbound
import app.dependencies.docker.DockerContainer
import app.dependencies.jaeger.Jaeger
import app.dependencies.mongodb.MongoDb
import app.dependencies.postgres.Postgres18
import app.dependencies.rabbitmq.RabbitMq
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import es.jvbabi.tui.table.buildTable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import util.buildStyledString

class ExposedCommand : SuspendingCliktCommand("exposed"), KoinComponent {

    private val postgres18 by inject<Postgres18>()
    private val androidDns by inject<Unbound>()
    private val jaeger by inject<Jaeger>()
    private val mongoDb by inject<MongoDb>()
    private val rabbitMq by inject<RabbitMq>()

    override suspend fun run() {
        println(buildStyledString { aqua { +"Exposed werkbank services" } })

        val exposedServices = mutableMapOf<String, ExposedService>()

        fun addPort(name: String, service: ExposedPort) {
            val value = exposedServices[name] ?: ExposedService(
                name = name,
                webs = emptyList(),
                ports = emptyList()
            )
            val updated = value.copy(ports = value.ports + service)
            exposedServices[name] = updated
        }

        fun addWeb(name: String, web: ExposedWeb) {
            val value = exposedServices[name] ?: ExposedService(
                name = name,
                webs = emptyList(),
                ports = emptyList()
            )
            val updated = value.copy(webs = value.webs + web)
            exposedServices[name] = updated
        }

        run postgres@{
            if (postgres18.container.getState() != DockerContainer.State.Running) return@postgres

            addPort("Postgres", ExposedPort(
                name = "Postgres 18",
                port = postgres18.postgresPort,
                protocol = "PostgreSQL",
                domains = listOf(postgres18.hostname),
            ))
        }

        run androidDns@{
            if (androidDns.getContainer().getState() !== DockerContainer.State.Running) return@androidDns
            addPort("Android DNS", ExposedPort(
                name = "DNS",
                port = 53,
                protocol = "DNS UDP/TCP",
                domains = emptyList(),
            ))
        }

        run jaeger@{
            if (jaeger.getContainer().getState() != DockerContainer.State.Running) return@jaeger

            addPort("Jaeger", ExposedPort(
                port = 4317,
                name = "OTLP gRPC",
                protocol = "TCP",
                domains = listOf(jaeger.jaegerHostname),
            ))
            addPort("Jaeger", ExposedPort(
                port = 4318,
                name = "OTLP HTTP",
                protocol = "TCP",
                domains = listOf(jaeger.jaegerHostname),
            ))
            addPort("Jaeger", ExposedPort(
                port = 14250,
                name = "Jaeger gRPC Collector Agent",
                protocol = "TCP/UDP",
                domains = listOf(jaeger.jaegerHostname),
            ))
            addPort("Jaeger", ExposedPort(
                port = 14268,
                name = "HTTP Thrift span",
                protocol = "TCP",
                domains = listOf(jaeger.jaegerHostname),
            ))
            addPort("Jaeger", ExposedPort(
                port = 9411,
                name = "Zipkin",
                protocol = "TCP",
                domains = listOf(jaeger.jaegerHostname),
            ))

            addWeb("Jaeger", ExposedWeb(
                name = "Web UI",
                domains = jaeger.webfacingDomains
            ))
        }

        run mongoDb@{
            if (mongoDb.mongoDatabaseContainer.getState() != DockerContainer.State.Running) return@mongoDb

            addPort("MongoDB", ExposedPort(
                name = "Database",
                port = mongoDb.mongoDbPort,
                protocol = "MongoDB",
                domains = listOf(mongoDb.mongoDatabaseHostname),
            ))
        }

        run mongoExpress@{
            if (mongoDb.mongoExpressContainer.getState() != DockerContainer.State.Running) return@mongoExpress

            addWeb("MongoDB", ExposedWeb(
                name = "Mongo Express",
                domains = listOf(mongoDb.mongoExpressDomain)
            ))
        }

        run rabbitMq@{
            if (rabbitMq.rabbitMqContainer.getState() != DockerContainer.State.Running) return@rabbitMq

            addPort("RabbitMQ", ExposedPort(
                name = "RabbitMQ Server",
                port = rabbitMq.rabbitMqPort,
                protocol = "AQMP",
                domains = listOf(rabbitMq.rabbitMqHostname),
            ))

            addWeb("RabbitMQ", web = ExposedWeb(
                name = "Management",
                domains = rabbitMq.webfacingDomains
            ))
        }

        if (exposedServices.isEmpty()) {
            println()
            println(buildStyledString { gray { italic { +"No services exposed." } } })
            return
        }

        exposedServices.forEach { (name, service) ->
            buildTable {
                row {
                    cell(colspan = 4) { +buildStyledString {
                        bold { blue { +name } }
                        gray { +" / " }
                        +"Ports"
                    } }
                }
                row {
                    cell { centered = true; +buildStyledString { bold { +"Name" } } }
                    cell { centered = true; +buildStyledString { bold { +"Port" } } }
                    cell { centered = true; +buildStyledString { bold { +"Protocol" } } }
                    cell { centered = true; +buildStyledString { bold { +"Domains" } } }
                }
                service.ports.forEach { port ->
                    row {
                        cell { +port.name }
                        cell { +buildStyledString { yellow { +port.port.toString() } } }
                        cell { +port.protocol }
                        cell { +buildStyledString {
                            +port.domains.joinToString(buildStyledString { gray { +", " } }) { buildStyledString { green { +it } } }
                        } }
                    }
                }
                row {
                    cell(colspan = 4) { +buildStyledString {
                        bold { blue { +name } }
                        gray { +" / " }
                        +"Services"
                    } }
                }
                row {
                    cell { centered = true; +buildStyledString { bold { +"Service" } } }
                    cell(colspan = 3) { centered = true; +buildStyledString { bold { +"Domains" } } }
                }
                service.webs.forEach { web ->
                    row {
                        cell { +web.name }
                        cell(colspan = 3) { +buildStyledString {
                            +web.domains.joinToString(buildStyledString { gray { +", " } }) { buildStyledString { green { +it } } }
                        } }
                    }
                }
            }.let { println(it) }
        }
    }
}

private data class ExposedService(
    val name: String,
    val webs: List<ExposedWeb>,
    val ports: List<ExposedPort>,
)

private data class ExposedWeb(
    val name: String,
    val domains: List<String>,
)

private data class ExposedPort(
    val name: String,
    val port: Int,
    val protocol: String,
    val domains: List<String>,
)