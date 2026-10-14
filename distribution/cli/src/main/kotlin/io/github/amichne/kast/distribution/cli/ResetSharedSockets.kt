package io.github.amichne.kast.distribution.cli

import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

internal sealed interface ResetSharedSocketCapture {
    class Captured
    internal constructor(private val process: ResetScopedProcess, private val sockets: List<ResetOwnedSharedSocket>) :
        ResetSharedSocketCapture {
        fun retireAfterExit(
            observation: LifecycleProcessObservation,
            observe: (ResetProcessObservation) -> Unit = {},
        ): LifecycleProcessObservation {
            val result = if (observation == LifecycleProcessObservation.Absent) retireAll() else socketRejected()
            observe(
                ResetProcessObservation(
                    process.snapshot.pid,
                    ResetProcessStage.SOCKET_RETIREMENT,
                    when (result) {
                        ResetEffect.Completed -> ResetProcessResult.RETIRED
                        is ResetEffect.Rejected -> ResetProcessResult.OBSERVATION_REJECTED
                    },
                )
            )
            return when (result) {
                ResetEffect.Completed -> observation
                is ResetEffect.Rejected -> LifecycleProcessObservation.Unavailable
            }
        }

        private fun retireAll(): ResetEffect {
            for (socket in sockets) {
                when (val result = socket.retire()) {
                    ResetEffect.Completed -> Unit
                    is ResetEffect.Rejected -> return result
                }
            }
            return ResetEffect.Completed
        }
    }

    data object Rejected : ResetSharedSocketCapture
}

/** The selected process owns the descriptor. The captured inode scopes later filesystem retirement. */
internal class ResetOwnedSharedSocket private constructor(private val path: Path, private val key: Any) {
    fun retire(): ResetEffect =
        try {
            if (Files.notExists(path, NOFOLLOW_LINKS)) ResetEffect.Completed else retirePresent()
        } catch (_: Exception) {
            socketRejected()
        }

    private fun retirePresent(): ResetEffect {
        val lockPath = path.resolveSibling("${path.fileName}.lock")
        FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            if (!Files.isRegularFile(lockPath, NOFOLLOW_LINKS)) return socketRejected()
            val lock = channel.tryLock() ?: return socketRejected()
            lock.use {
                if (Files.notExists(path, NOFOLLOW_LINKS)) return ResetEffect.Completed
                if (socketKey(path) != key || inactive(path) != ResetSocketReachability.INACTIVE)
                    return socketRejected()
                if (socketKey(path) != key) return socketRejected()
                Files.delete(path)
                return ResetEffect.Completed
            }
        }
    }

    companion object {
        fun capture(
            process: ResetScopedProcess,
            inspect: (List<String>) -> ProcessObservation = { runBounded(it) },
            observe: (ResetProcessObservation) -> Unit = {},
        ): ResetSharedSocketCapture {
            val result = captureProcess(process, inspect)
            observe(
                ResetProcessObservation(
                    process.snapshot.pid,
                    ResetProcessStage.SOCKET_CAPTURE,
                    when (result) {
                        is ResetSharedSocketCapture.Captured -> ResetProcessResult.IDENTITY_VERIFIED
                        ResetSharedSocketCapture.Rejected -> ResetProcessResult.OBSERVATION_REJECTED
                    },
                )
            )
            return result
        }

        private fun captureProcess(
            process: ResetScopedProcess,
            inspect: (List<String>) -> ProcessObservation,
        ): ResetSharedSocketCapture {
            val executable =
                listOf("/usr/sbin/lsof", "/usr/bin/lsof").firstOrNull { Files.isExecutable(Path.of(it)) }
                    ?: return ResetSharedSocketCapture.Rejected
            val result =
                inspect(listOf(executable, "-nP", "-a", "-p", process.snapshot.pid.toString(), "-U", "-F", "pn"))
            if (result !is ProcessObservation.Exited) return ResetSharedSocketCapture.Rejected
            if (result.code in 0..1 && result.output.isEmpty())
                return ResetSharedSocketCapture.Captured(process, emptyList())
            if (result.code != 0) return ResetSharedSocketCapture.Rejected
            return captureDocument(process, result.output)
        }

        private fun captureDocument(process: ResetScopedProcess, document: String): ResetSharedSocketCapture =
            try {
                val lines = document.lineSequence().filter { it.isNotEmpty() }.toList()
                if (lines.size > MAXIMUM_SOCKET_RECORDS || lines.firstOrNull() != "p${process.snapshot.pid}")
                    return ResetSharedSocketCapture.Rejected
                if (lines.drop(1).any { !it.startsWith('n') && !Regex("f[0-9]+").matches(it) })
                    return ResetSharedSocketCapture.Rejected
                val sockets = mutableListOf<ResetOwnedSharedSocket>()
                val paths =
                    lines
                        .drop(1)
                        .filter { it.startsWith("n/") }
                        .map { Path.of(it.drop(1)) }
                        .filter { it.endsWith(Path.of("app-server-control/app-server-control.sock")) }
                for (path in paths) {
                    val key = socketKey(path) ?: return ResetSharedSocketCapture.Rejected
                    sockets += ResetOwnedSharedSocket(path, key)
                }
                ResetSharedSocketCapture.Captured(process, sockets)
            } catch (_: Exception) {
                ResetSharedSocketCapture.Rejected
            }
    }
}

private fun socketKey(path: Path): Any? {
    if (!path.isAbsolute || path.normalize() != path || path.parent.toRealPath() != path.parent) return null
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    val mode = (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Number).toInt()
    return if (!attributes.isSymbolicLink && mode and SOCKET_MODE_MASK == SOCKET_MODE) attributes.fileKey() else null
}

private fun inactive(path: Path): ResetSocketReachability =
    try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
            socket.configureBlocking(false)
            socket.connect(UnixDomainSocketAddress.of(path))
            ResetSocketReachability.ACTIVE
        }
    } catch (_: ConnectException) {
        ResetSocketReachability.INACTIVE
    } catch (_: Exception) {
        ResetSocketReachability.REJECTED
    }

private fun socketRejected() = ResetEffect.Rejected(ForceResetFailure.PROCESS_RETIREMENT_REJECTED)

private enum class ResetSocketReachability {
    INACTIVE,
    ACTIVE,
    REJECTED,
}

private const val SOCKET_MODE_MASK = 0xF000
private const val SOCKET_MODE = 0xC000
private const val MAXIMUM_SOCKET_RECORDS = 1024
