package app.dependencies.postgres

import app.dependencies.docker.DockerContainer
import app.repository.ProjectRepository
import app.storage.isDevMode
import app.storage.storageRoot
import es.jvbabi.docker.kt.api.container.VolumeBind
import es.jvbabi.docker.kt.docker.DockerClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.getValue

class Postgres18: KoinComponent {
    private val projectRepository by inject<ProjectRepository>()
    private val dockerClient by inject<DockerClient>()

    private val postgresRoot = storageRoot
        .resolve("postgres")
        .resolve("data")

    val container = DockerContainer(
        image = "postgres:18.1-alpine3.22",
        name = buildString {
            append("werkbank-")
            if (isDevMode) append("dev-")
            append("postgres-18")
        },
        ports = listOf("5432:5432"),
        volumes = mapOf(
            VolumeBind.Host(postgresRoot.absolutePath) to "/var/lib/postgresql/data",
        ),
        environment = mapOf(
            "POSTGRES_PASSWORD" to "werkbank",
            "POSTGRES_USER" to "werkbank",
        ),
    )

    suspend fun initialize() {
        if (!postgresRoot.exists()) postgresRoot.mkdir(recursive = true)
        if (container.getState() == DockerContainer.State.NotExisting) container.create()
        createProjectDatabases()
    }

    suspend fun createProjectDatabases() {
        container.withRunning {
            val databaseResult = dockerClient.containers.runCommand(
                containerId = container.getId()!!,
                environment = mapOf(
                    "PGPASSWORD" to "werkbank"
                ),
                command = listOf(
                    "psql",
                    "-U", "werkbank",
                    "-t",
                    "-A",
                    "-c",
                    "SELECT datname FROM pg_database WHERE datname <> ALL ('{template0,template1,postgres}')"
                )
            )
            require(databaseResult.exitCode == 0) { "Failed to list project databases: ${databaseResult.output}" }
            val existingDatabases = databaseResult.output
                .lines()
                .filter { it.isNotBlank() }
                .toSet()

            val projects = projectRepository.getAllProjects()
            val desiredDatabases = projects
                .flatMap { project ->
                    project.getConfig()
                        .dependencies
                        ?.postgres
                        ?.postgres18
                        ?.databases
                        .orEmpty().map { dbname ->
                            project.id + "_" + dbname.substringAfter(project.id + "_")
                        }
                }
                .toSet()

            (desiredDatabases - existingDatabases).forEach { dbname ->
                val result = dockerClient.containers.runCommand(
                    containerId = container.getId()!!,
                    environment = mapOf(
                        "PGPASSWORD" to "werkbank"
                    ),
                    command = listOf(
                        "createdb",
                        "-U", "werkbank",
                        dbname
                    )
                )
                require(result.exitCode == 0) { "Failed to create database $dbname: ${result.output}" }
            }

            val protectedDatabases = setOf("postgres", "template0", "template1", "werkbank")
            (existingDatabases - desiredDatabases)
                .filter { it !in protectedDatabases }
                .forEach { dbname ->
                    val result = dockerClient.containers.runCommand(
                        containerId = container.getId()!!,
                        environment = mapOf(
                            "PGPASSWORD" to "werkbank"
                        ),
                        command = listOf(
                            "dropdb",
                            "-U", "werkbank",
                            dbname
                        )
                    )
                    require(result.exitCode == 0) { "Failed to drop database $dbname: ${result.output}" }
                }
        }
    }
}