package io.github.amichne.kast.runtime.hosted

import com.google.gson.Gson
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.UUID

/** A descriptor proves only an absent PID. A live or reused PID is always a conflict. */
internal class DeadHostedEndpointOwner private constructor(private val pid: Long) {
    fun remainsAbsent(): Boolean = ProcessHandle.of(pid).isEmpty

    companion object {
        fun read(
            path: Path,
            root: CanonicalWorkspaceRoot,
            socket: Path,
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
                val pid = descriptor.get("hostPid").asString.toLongOrNull() ?: return rejected()
                if (pid <= 0 || ProcessHandle.of(pid).isPresent) return rejected()
                val host = descriptor.get("host").asString
                if (UUID.fromString(host).toString() != host) return rejected()
                val expected =
                    Gson()
                        .toJsonTree(
                            mapOf(
                                "type" to "KAST_IDE_ENDPOINT",
                                "protocol" to HostedEndpointCapabilities.protocol,
                                "root" to root.value,
                                "socket" to socket.toString(),
                                "hostPid" to pid,
                                "host" to host,
                                "querySchema" to HostedReadCapabilities.querySchema,
                                "operations" to HostedEndpointCapabilities.operations,
                            )
                        )
                if (descriptor != expected) return rejected()
                Refinement.Refined(DeadHostedEndpointOwner(pid))
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

        private fun rejected() = Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT)
    }
}

private const val MAX_DESCRIPTOR_BYTES = 16384
