package app.dependencies.docker

import app.dependencies.cli.runCommand
import app.storage.isDevMode

class DockerNetwork {
    val name = "werkbank" + if (isDevMode) "-dev" else ""

    fun initialize() {
        if (getStatus() == Status.Missing) create()
    }

    enum class Status {
        Created, Missing
    }

    fun getStatus(): Status {
        val response = runCommand(
            command = "docker",
            "network",
            "ls",
            "--filter",
            "name=$name",
            "--format",
            "{{.Name}}"
        )
        val doesNetworkExist = response.stdout?.lines()?.firstOrNull()?.ifBlank { null } == name
        return if (doesNetworkExist) Status.Created
        else Status.Missing
    }

    fun create() {
        runCommand(
            command = "docker",
            "network",
            "create",
            "--driver",
            "bridge",
            "--attachable",
            name,
            "--label",
            "compose.project=werkbank"
        )
    }
}