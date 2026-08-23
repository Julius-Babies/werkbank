package app.storage

import es.jvbabi.kfile.File

/**
 * Holds the pid of the tunnel that owns this account's slot on this machine.
 *
 * Written only once the server accepted a tunnel and removed when that tunnel goes away, so a second
 * CLI that gets refused can tell whether the tunnel in its way is local — and offer to stop it.
 */
object TunnelPidFile {

    private val file: File get() = storageRoot.resolve("tunnel.pid")

    fun write(pid: Int) {
        runCatching { file.writeText(pid.toString()) }
    }

    fun read(): Int? = runCatching {
        if (file.exists()) file.readText().trim().toIntOrNull() else null
    }.getOrNull()

    /** Removes the file, but only while it still points at [pid], so a successor is left alone. */
    fun clear(pid: Int) {
        runCatching { if (read() == pid) file.delete() }
    }
}
