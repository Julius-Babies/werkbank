package app.dependencies.docker

import es.jvbabi.docker.kt.api.container.Container

/** macOS ships `lsof`, which reports both TCP listeners and bound UDP sockets. */
actual fun findProcessUsingPort(port: Int, protocol: Container.PortBinding.Protocol): PortUsingProcess? =
    findProcessUsingPortWithLsof(port, protocol)
