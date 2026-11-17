package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.BrokerSocketPath
import io.github.amichne.kast.appserver.runtime.OwnedUnixSocket
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable

/** Published only after this process binds the coordinator's private socket. */
@Serializable
internal data class SemanticCoordinatorPeer(
    val pid: Long,
    val startedAt: String,
    val executable: String,
    val revision: CoordinatorExecutableRevision,
    val socketKey: String,
) {
    fun owns(socket: BrokerSocketPath, expectedExecutable: Path): Boolean {
        if (pid <= 0 || expectedExecutable.toString() != executable) return false
        val process = ProcessHandle.of(pid).orElse(null) ?: return false
        val observedSocket = OwnedUnixSocket.capture(socket) ?: return false
        return capture(process, observedSocket) == this
    }

    companion object {
        fun capture(process: ProcessHandle, socket: OwnedUnixSocket): SemanticCoordinatorPeer? {
            if (!process.isAlive) return null
            val info = process.info()
            val started = info.startInstant().orElse(null) ?: return null
            val command = info.command().orElse(null) ?: return null
            val executable =
                try {
                    Path.of(command).toRealPath()
                } catch (_: IOException) {
                    return null
                } catch (_: SecurityException) {
                    return null
                } catch (_: IllegalArgumentException) {
                    return null
                }
            val key = socket.currentFileKey() ?: return null
            val revision = CoordinatorExecutableRevision.capture(executable) ?: return null
            return SemanticCoordinatorPeer(process.pid(), started.toString(), executable.toString(), revision, key)
        }
    }
}

@Serializable
internal data class CoordinatorExecutableRevision(
    val device: Long,
    val inode: Long,
    val size: Long,
    val changedAt: String,
) {
    companion object {
        fun capture(path: Path): CoordinatorExecutableRevision? {
            val stat =
                try {
                    Files.readAttributes(path, "unix:dev,ino,size,ctime,isRegularFile", NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    return null
                } catch (_: SecurityException) {
                    return null
                } catch (_: UnsupportedOperationException) {
                    return null
                }
            if (stat["isRegularFile"] != true) return null
            val device = (stat["dev"] as? Number)?.toLong() ?: return null
            val inode = (stat["ino"] as? Number)?.toLong() ?: return null
            val size = (stat["size"] as? Number)?.toLong() ?: return null
            val changedAt = stat["ctime"] as? java.nio.file.attribute.FileTime ?: return null
            return CoordinatorExecutableRevision(device, inode, size, changedAt.toString())
        }
    }
}
