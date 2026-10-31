package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.ObservedProcessState

/** Raw process metadata stays untrusted until exact target admission. */
internal data class ManagedProcessMetadata(
    val pid: Long,
    val alive: Boolean,
    val owner: String?,
    val executable: String?,
    val started: Long?,
)

internal sealed interface ManagedProcessCapture {
    data object OtherUser : ManagedProcessCapture

    data object OtherExecutable : ManagedProcessCapture

    data object Exited : ManagedProcessCapture

    data object Unavailable : ManagedProcessCapture

    data class NeedsState(val pid: Long) : ManagedProcessCapture

    data class Captured(val process: ManagedIdeProcess) : ManagedProcessCapture
}

internal fun admitManagedProcess(
    metadata: ManagedProcessMetadata,
    executable: String,
    user: String,
): ManagedProcessCapture {
    if (metadata.owner != null && metadata.owner != user) return ManagedProcessCapture.OtherUser
    if (!metadata.alive) return ManagedProcessCapture.Exited
    val command = metadata.executable ?: return ManagedProcessCapture.NeedsState(metadata.pid)
    if (command != executable) return ManagedProcessCapture.OtherExecutable
    val started = metadata.started ?: return ManagedProcessCapture.Unavailable
    if (metadata.owner == null) return ManagedProcessCapture.Unavailable
    return ManagedProcessCapture.Captured(ManagedIdeProcess(metadata.pid, started, command))
}

internal fun admitMissingManagedExecutable(state: ObservedProcessState): ManagedProcessCapture =
    when (state) {
        ObservedProcessState.EXITED -> ManagedProcessCapture.Exited
        ObservedProcessState.ACTIVE,
        ObservedProcessState.UNAVAILABLE -> ManagedProcessCapture.Unavailable
    }
