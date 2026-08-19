package app.dependencies.docker

import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import es.jvbabi.docker.kt.api.container.Container

/** `users:(("nginx",pid=1234,fd=6))` in the `ss` output. */
private val ssProcessRegex = Regex("""\(\("([^"]+)",pid=(\d+)""")

/**
 * Prefers `ss` from iproute2, which is present on far more distributions than `lsof`.
 * It only reports pids of processes owned by the current user unless run as root, so
 * `lsof` is used as a fallback.
 */
actual fun findProcessUsingPort(port: Int, protocol: Container.PortBinding.Protocol): PortUsingProcess? {
    val protocolFlag = when (protocol) {
        Container.PortBinding.Protocol.TCP -> "-t"
        Container.PortBinding.Protocol.UDP -> "-u"
    }

    val output = runCatching {
        Command("ss")
            .args("-H", "-l", "-n", "-p", protocolFlag, "sport", "=", ":$port")
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Null)
            .spawn()
            .waitWithOutput()
    }.getOrNull()

    val match = output?.stdout?.let { ssProcessRegex.find(it) }
    if (match != null) {
        val (name, pid) = match.destructured
        pid.toIntOrNull()?.let { return PortUsingProcess(pid = it, name = name) }
    }

    return findProcessUsingPortWithLsof(port, protocol)
}
