package io.github.amichne.kast.distribution.cli

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path

internal enum class ResetSocketPeerProof {
    PROVEN,
    REJECTED,
}

/** Darwin's Unix socket device and peer records bind one new connection to the selected process. */
internal object ResetSocketPeerOwnership {
    fun prove(
        path: Path,
        pid: Long,
        executable: String,
        inspect: (List<String>) -> ProcessObservation,
    ): ResetSocketPeerProof =
        try {
            val caller = ProcessHandle.current().pid()
            val before = descriptors(executable, setOf(caller), inspect) ?: return ResetSocketPeerProof.REJECTED
            SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
                socket.configureBlocking(false)
                if (!socket.connect(UnixDomainSocketAddress.of(path))) return ResetSocketPeerProof.REJECTED
                val after = descriptors(executable, setOf(caller, pid), inspect) ?: return ResetSocketPeerProof.REJECTED
                val current = after.filter { it.pid == caller }.toSet()
                if (!current.containsAll(before)) return ResetSocketPeerProof.REJECTED
                val connection = (current - before).singleOrNull() ?: return ResetSocketPeerProof.REJECTED
                val peer = connection.name.removePrefix("->")
                if (!DEVICE.matches(peer)) return ResetSocketPeerProof.REJECTED
                val owner = after.singleOrNull { it.pid == pid && it.device == peer && it.name == path.toString() }
                if (owner == null) ResetSocketPeerProof.REJECTED else ResetSocketPeerProof.PROVEN
            }
        } catch (_: Exception) {
            ResetSocketPeerProof.REJECTED
        }

    private fun descriptors(
        executable: String,
        pids: Set<Long>,
        inspect: (List<String>) -> ProcessObservation,
    ): Set<UnixDescriptor>? {
        val result = inspect(listOf(executable, "-nP", "-a", "-p", pids.joinToString(","), "-U", "-F", "pfdn"))
        if (result !is ProcessObservation.Exited) return null
        if (result.code in 0..1 && result.output.isEmpty()) return emptySet()
        if (result.code != 0) return null
        return parse(result.output, pids)
    }

    private fun parse(document: String, pids: Set<Long>): Set<UnixDescriptor>? {
        val lines = document.lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.size > MAXIMUM_PEER_RECORDS) return null
        val result =
            lines.fold<_, PeerDescriptorState>(PeerDescriptorState.Process(emptySet())) { state, line ->
                state.advance(line, pids)
            }
        return (result as? PeerDescriptorState.Descriptor)?.records
    }
}

private data class UnixDescriptor(val pid: Long, val descriptor: String, val device: String, val name: String)

private sealed interface PeerDescriptorState {
    fun advance(line: String, pids: Set<Long>): PeerDescriptorState

    data class Process(val records: Set<UnixDescriptor>) : PeerDescriptorState {
        override fun advance(line: String, pids: Set<Long>): PeerDescriptorState {
            if (!line.startsWith('p')) return Rejected
            val pid = line.drop(1).toLongOrNull()?.takeIf { it in pids } ?: return Rejected
            return Descriptor(pid, records)
        }
    }

    data class Descriptor(val pid: Long, val records: Set<UnixDescriptor>) : PeerDescriptorState {
        override fun advance(line: String, pids: Set<Long>): PeerDescriptorState =
            when (line.first()) {
                'p' -> Process(records).advance(line, pids)
                'f' -> {
                    val descriptor = line.drop(1)
                    if (FILE_DESCRIPTOR.matches(descriptor)) Device(pid, descriptor, records) else Rejected
                }
                else -> Rejected
            }
    }

    data class Device(val pid: Long, val descriptor: String, val records: Set<UnixDescriptor>) : PeerDescriptorState {
        override fun advance(line: String, pids: Set<Long>): PeerDescriptorState {
            val device = line.drop(1)
            return if (line.startsWith('d') && DEVICE.matches(device)) Name(pid, descriptor, device, records)
            else Rejected
        }
    }

    data class Name(
        val pid: Long,
        val descriptor: String,
        val device: String,
        val records: Set<UnixDescriptor>,
    ) : PeerDescriptorState {
        override fun advance(line: String, pids: Set<Long>): PeerDescriptorState {
            if (!line.startsWith('n') || line.length == 1) return Rejected
            if (records.any { it.pid == pid && it.descriptor == descriptor }) return Rejected
            return Descriptor(pid, records + UnixDescriptor(pid, descriptor, device, line.drop(1)))
        }
    }

    data object Rejected : PeerDescriptorState {
        override fun advance(line: String, pids: Set<Long>): PeerDescriptorState = this
    }
}

private val DEVICE = Regex("0x[0-9a-f]+")
private val FILE_DESCRIPTOR = Regex("[0-9]+")
private const val MAXIMUM_PEER_RECORDS = 1024
