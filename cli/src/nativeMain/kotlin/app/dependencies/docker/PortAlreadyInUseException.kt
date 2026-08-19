package app.dependencies.docker

import es.jvbabi.docker.kt.api.container.Container

/**
 * Thrown before a container is started when one of its host ports is already taken.
 * Docker itself only reports this as an opaque 500 ("port is already allocated"), without
 * naming whoever holds the port.
 */
sealed class PortAlreadyInUseException(
    val port: Int,
    val protocol: Container.PortBinding.Protocol,
    val requestedBy: String,
    message: String,
) : Exception(message) {

    /** The port is published by another Docker container. */
    class Docker(
        port: Int,
        protocol: Container.PortBinding.Protocol,
        requestedBy: String,
        val containerId: String,
        val containerName: String,
    ) : PortAlreadyInUseException(
        port = port,
        protocol = protocol,
        requestedBy = requestedBy,
        message = "Cannot start '$requestedBy': port $port/${protocol.name.lowercase()} is already published by " +
                "Docker container '$containerName' (${containerId.take(12)})."
    )

    /** The port is held by a process on the host. */
    class Process(
        port: Int,
        protocol: Container.PortBinding.Protocol,
        requestedBy: String,
        val pid: Int,
        val processName: String,
    ) : PortAlreadyInUseException(
        port = port,
        protocol = protocol,
        requestedBy = requestedBy,
        message = "Cannot start '$requestedBy': port $port/${protocol.name.lowercase()} is already used by " +
                "process '$processName' (pid $pid)."
    )
}
