package io.github.amichne.kast.runtime.hosted

import com.google.gson.JsonObject
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.UUID

/** Retirement requires an absent process or the same kernel-proven Linux zombie. */
internal class DeadHostedEndpointOwner
private constructor(
    private val pid: Long,
    private val admitted: HostedEndpointOwnerProcess,
    private val observe: (Long) -> HostedEndpointOwnerProcess,
) {
    fun remainsExited(): Boolean =
        when (val current = observe(pid)) {
            HostedEndpointOwnerProcess.Absent -> true
            is HostedEndpointOwnerProcess.Zombie -> current == admitted
            HostedEndpointOwnerProcess.Active,
            HostedEndpointOwnerProcess.Unavailable -> false
        }

    companion object {
        /** Process metadata reads stay in the existing endpoint ownership I/O boundary. */
        fun observeProcess(pid: Long): HostedEndpointOwnerProcess =
            try {
                when {
                    pid <= 0 -> HostedEndpointOwnerProcess.Unavailable
                    ProcessHandle.of(pid).isEmpty -> HostedEndpointOwnerProcess.Absent
                    System.getProperty("os.name") != "Linux" -> HostedEndpointOwnerProcess.Active
                    else -> {
                        val bytes =
                            Files.newInputStream(Path.of("/proc/$pid/stat"), READ, NOFOLLOW_LINKS).use {
                                it.readNBytes(MAXIMUM_PROCESS_STAT_BYTES + 1)
                            }
                        if (bytes.size > MAXIMUM_PROCESS_STAT_BYTES) HostedEndpointOwnerProcess.Unavailable
                        else parseLinuxOwnerProcess(pid, bytes.decodeToString())
                    }
                }
            } catch (_: java.io.IOException) {
                // A failed procfs read is not proof that the process exited.
                HostedEndpointOwnerProcess.Unavailable
            } catch (_: SecurityException) {
                HostedEndpointOwnerProcess.Unavailable
            }

        fun read(
            path: Path,
            root: CanonicalWorkspaceRoot,
            socket: Path,
            observe: (Long) -> HostedEndpointOwnerProcess = HostedEndpointOwnerProcess::observe,
        ): Refinement<DeadHostedEndpointOwner, HostedEndpointFailure> {
            return try {
                val raw =
                    when (val content = readDescriptor(path)) {
                        is Refinement.Refined -> content.value
                        is Refinement.Rejected -> return content
                    }
                val descriptor =
                    when (val parsed = HostedEndpointDescriptor.parse(raw)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> return parsed
                    }
                if (!matchesIdentity(descriptor, root, socket)) return rejected()
                val pid = descriptor.get("hostPid")?.asString?.toLongOrNull() ?: return rejected()
                if (pid <= 0) return rejected()
                val process = observe(pid)
                if (process !is HostedEndpointOwnerProcess.Zombie && process != HostedEndpointOwnerProcess.Absent)
                    return rejected()
                val host = descriptor.get("host")?.asString ?: return rejected()
                if (UUID.fromString(host).toString() != host) return rejected()
                Refinement.Refined(DeadHostedEndpointOwner(pid, process, observe))
            } catch (_: java.io.IOException) {
                rejected()
            } catch (_: RuntimeException) {
                rejected()
            }
        }

        private fun readDescriptor(path: Path): Refinement<String, HostedEndpointFailure> {
            val buffer = ByteBuffer.allocate(MAX_DESCRIPTOR_BYTES + 1)
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    /* Bounded regular file. */
                }
            }
            if (!buffer.hasRemaining()) return rejected()
            buffer.flip()
            return Refinement.Refined(Charsets.UTF_8.newDecoder().decode(buffer).toString())
        }

        private fun matchesIdentity(
            descriptor: JsonObject,
            root: CanonicalWorkspaceRoot,
            socket: Path,
        ): Boolean =
            descriptor.get("type")?.asString == "KAST_IDE_ENDPOINT" &&
                descriptor.get("root")?.asString == root.value &&
                descriptor.get("socket")?.asString == socket.toString()

        private fun rejected() = Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
    }
}

private const val MAX_DESCRIPTOR_BYTES = 16384

private const val MAXIMUM_PROCESS_STAT_BYTES = 4096
