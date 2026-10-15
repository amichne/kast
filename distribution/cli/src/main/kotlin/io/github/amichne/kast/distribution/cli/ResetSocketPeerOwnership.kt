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
        effects: ResetPeerEffects = ResetPeerEffects(),
        inspect: (List<String>) -> ProcessObservation,
    ): ResetSocketPeerProof =
        try {
            val (unchanged, observe) = effects
            val caller = ProcessHandle.current().pid()
            val before =
                descriptors(executable, setOf(caller), inspect)
                    ?: return record(pid, ResetProcessResult.OBSERVATION_REJECTED, observe)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
                socket.configureBlocking(false)
                if (!socket.connect(UnixDomainSocketAddress.of(path)))
                    return record(pid, ResetProcessResult.OBSERVATION_REJECTED, observe)
                val first =
                    descriptors(executable, setOf(caller, pid), inspect)
                        ?: return record(pid, ResetProcessResult.OBSERVATION_REJECTED, observe)
                when (val admission = ResetPeerConnection.admit(caller, before, first)) {
                    ResetPeerAdmission.Rejected -> record(pid, ResetProcessResult.PEER_REJECTED, observe)
                    is ResetPeerAdmission.Admitted ->
                        awaitOwner(
                            ResetPeerInspection(path, pid, executable, unchanged, observe, inspect),
                            admission.connection,
                            first,
                        )
                }
            }
        } catch (_: Exception) {
            record(pid, ResetProcessResult.OBSERVATION_REJECTED, effects.observe)
        }

    private fun awaitOwner(
        inspection: ResetPeerInspection,
        connection: ResetPeerConnection,
        initial: Set<UnixDescriptor>,
    ): ResetSocketPeerProof {
        val pid = inspection.pid
        val observe = inspection.observe
        var after = initial
        repeat(MAXIMUM_PEER_OBSERVATIONS) { observation ->
            if (!inspection.unchanged()) return record(pid, ResetProcessResult.SOCKET_CHANGED, observe)
            if (observation > 0) {
                after =
                    descriptors(inspection.executable, setOf(connection.caller, pid), inspection.inspect)
                        ?: return record(pid, ResetProcessResult.OBSERVATION_REJECTED, observe)
            }
            val outcome = inspection.currentOutcome(connection.owner(after, pid, inspection.path))
            record(pid, outcome, observe)
            if (outcome != ResetProcessResult.PEER_PENDING) {
                return if (outcome == ResetProcessResult.IDENTITY_VERIFIED) ResetSocketPeerProof.PROVEN
                else ResetSocketPeerProof.REJECTED
            }
        }
        return record(pid, ResetProcessResult.OBSERVATION_REJECTED, observe)
    }

    private fun record(
        pid: Long,
        result: ResetProcessResult,
        observe: (ResetProcessObservation) -> Unit,
    ): ResetSocketPeerProof {
        observe(ResetProcessObservation(pid, ResetProcessStage.SOCKET_PEER, result))
        return if (result == ResetProcessResult.IDENTITY_VERIFIED) ResetSocketPeerProof.PROVEN
        else ResetSocketPeerProof.REJECTED
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

internal data class ResetPeerEffects(
    val unchanged: () -> Boolean = { true },
    val observe: (ResetProcessObservation) -> Unit = {},
)

private data class ResetPeerInspection(
    val path: Path,
    val pid: Long,
    val executable: String,
    val unchanged: () -> Boolean,
    val observe: (ResetProcessObservation) -> Unit,
    val inspect: (List<String>) -> ProcessObservation,
) {
    fun currentOutcome(outcome: ResetProcessResult): ResetProcessResult =
        if (unchanged()) outcome else ResetProcessResult.SOCKET_CHANGED
}

private sealed interface ResetPeerAdmission {
    data class Admitted(val connection: ResetPeerConnection) : ResetPeerAdmission

    data object Rejected : ResetPeerAdmission
}

private class ResetPeerConnection
private constructor(
    val caller: Long,
    private val current: Set<UnixDescriptor>,
    private val peer: String,
) {
    fun owner(records: Set<UnixDescriptor>, pid: Long, path: Path): ResetProcessResult {
        if (records.filter { it.pid == caller }.toSet() != current) return ResetProcessResult.PEER_REJECTED
        val owners = records.filter { it.pid == pid && it.device == peer }
        return when {
            owners.isEmpty() -> ResetProcessResult.PEER_PENDING
            owners.size == 1 && owners.single().name == path.toString() -> ResetProcessResult.IDENTITY_VERIFIED
            else -> ResetProcessResult.PEER_REJECTED
        }
    }

    companion object {
        fun admit(caller: Long, before: Set<UnixDescriptor>, after: Set<UnixDescriptor>): ResetPeerAdmission {
            val current = after.filter { it.pid == caller }.toSet()
            if (!current.containsAll(before)) return ResetPeerAdmission.Rejected
            val connection = (current - before).singleOrNull() ?: return ResetPeerAdmission.Rejected
            val peer = connection.name.removePrefix("->")
            return if (DEVICE.matches(peer)) ResetPeerAdmission.Admitted(ResetPeerConnection(caller, current, peer))
            else ResetPeerAdmission.Rejected
        }
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

private const val MAXIMUM_PEER_OBSERVATIONS = 3
