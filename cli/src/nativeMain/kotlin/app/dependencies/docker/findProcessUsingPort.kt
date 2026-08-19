package app.dependencies.docker

import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import es.jvbabi.docker.kt.api.container.Container

/** A host process holding a port, as reported by the platform's own tooling. */
data class PortUsingProcess(
    val pid: Int,
    val name: String
)

/**
 * Returns the process listening on [port], or null if the port is free or the owner
 * cannot be determined (e.g. the process belongs to another user and the platform
 * tooling hides its pid).
 */
expect fun findProcessUsingPort(port: Int, protocol: Container.PortBinding.Protocol): PortUsingProcess?

/**
 * Shared `lsof` lookup. Field mode (`-Fpc`) prints one `p<pid>` line per process,
 * followed by its `c<command>` line, which keeps the parsing independent of the locale
 * and column layout of the human-readable output.
 */
internal fun findProcessUsingPortWithLsof(port: Int, protocol: Container.PortBinding.Protocol): PortUsingProcess? {
    val selector = when (protocol) {
        Container.PortBinding.Protocol.TCP -> listOf("-iTCP:$port", "-sTCP:LISTEN")
        Container.PortBinding.Protocol.UDP -> listOf("-iUDP:$port")
    }

    // lsof exits with 1 when nothing matches, so the status is not checked.
    val output = runCatching {
        Command("lsof")
            .args(listOf("-nP", "-Fpc") + selector)
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Null)
            .spawn()
            .waitWithOutput()
    }.getOrNull() ?: return null

    var pid: Int? = null
    output.stdout.orEmpty().lineSequence().forEach { line ->
        when (line.firstOrNull()) {
            'p' -> pid = line.drop(1).trim().toIntOrNull()
            'c' -> pid?.let { return PortUsingProcess(pid = it, name = line.drop(1).trim()) }
        }
    }
    return null
}
