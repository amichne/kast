package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.distribution.contract.HostExitIndexState
import io.github.amichne.kast.distribution.contract.HostExitReason
import io.github.amichne.kast.distribution.contract.HostExitRecord
import io.github.amichne.kast.distribution.managed.ManagedHeadlessInstallation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeHostRuntimeIdentity
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal sealed interface HostChildRetention {
    fun current(): Process?

    fun record(process: Process)

    data object Discarded : HostChildRetention {
        override fun current(): Process? = null

        override fun record(process: Process) = Unit
    }

    class Retained : HostChildRetention {
        private val process = AtomicReference<Process?>()

        override fun current(): Process? = process.get()

        override fun record(process: Process) = this.process.set(process)
    }
}

data class HostIncarnation
internal constructor(
    val ideaHome: String,
    val profile: String,
    val hostLifetime: String,
    val pid: Long,
    val processStartEpochMillis: Long,
    val executable: String,
)

sealed interface HostExitCause {
    /** The Host recorded the exact stop request. The process exit is separate evidence. */
    data class RequestedStop(val reason: HostExitReason, val index: HostExitIndexState) : HostExitCause

    data object Unknown : HostExitCause
}

sealed interface HostExit {
    val incarnation: HostIncarnation
    val cause: HostExitCause

    data class OwnedChild(
        override val incarnation: HostIncarnation,
        val status: Int,
        override val cause: HostExitCause,
    ) : HostExit

    data class Adopted(override val incarnation: HostIncarnation, override val cause: HostExitCause) : HostExit
}

sealed interface ObservedHost {
    val incarnation: HostIncarnation
    val target: IdeProjectTarget
    val exit: CompletionStage<HostExit>

    class OwnedChild
    internal constructor(
        override val incarnation: HostIncarnation,
        override val target: IdeProjectTarget,
        override val exit: CompletionStage<HostExit>,
    ) : ObservedHost

    class Adopted
    internal constructor(
        override val incarnation: HostIncarnation,
        override val target: IdeProjectTarget,
        override val exit: CompletionStage<HostExit>,
    ) : ObservedHost
}

enum class HostIncarnationFailure {
    INSPECTION_REJECTED,
    HOST_REPLACED,
    PROJECT_ABSENT,
    PROJECT_AMBIGUOUS,
    PROJECT_REPLACED,
    PROCESS_UNAVAILABLE,
    START_MISMATCH,
    EXECUTABLE_MISMATCH,
    OWNERSHIP_AMBIGUOUS,
}

internal fun observeManagedHost(
    installation: ManagedHeadlessInstallation,
    inspected: IdeLifecycleResult.Inspected,
    target: IdeProjectTarget,
    child: Process?,
): Refinement<ObservedHost, HostIncarnationFailure> {
    inspected.targetRejection(target)?.let {
        return Refinement.Rejected(it)
    }
    val identity = inspected.runtimeIdentity ?: return Refinement.Rejected(HostIncarnationFailure.INSPECTION_REJECTED)
    val incarnation = identity.incarnation(installation.launch.executable)
    if (child != null && child.isAlive) return observeChild(child, incarnation, target, installation.state)
    return adopt(incarnation, target, installation.state)
}

private fun IdeLifecycleResult.Inspected.targetRejection(target: IdeProjectTarget): HostIncarnationFailure? {
    if (host != target.host) return HostIncarnationFailure.HOST_REPLACED
    val projects = projects.filter { it.target.root == target.root }
    return when {
        projects.isEmpty() -> HostIncarnationFailure.PROJECT_ABSENT
        projects.size != 1 -> HostIncarnationFailure.PROJECT_AMBIGUOUS
        projects.single().target != target -> HostIncarnationFailure.PROJECT_REPLACED
        else -> null
    }
}

private fun IdeHostRuntimeIdentity.incarnation(executable: String) =
    HostIncarnation(ideaHome, profile, hostLifetime, pid, processStartEpochMillis, executable)

private fun observeChild(
    child: Process,
    incarnation: HostIncarnation,
    target: IdeProjectTarget,
    state: Path,
): Refinement<ObservedHost, HostIncarnationFailure> {
    if (child.pid() != incarnation.pid || child.toHandle().startedMillis() != incarnation.processStartEpochMillis)
        return Refinement.Rejected(HostIncarnationFailure.OWNERSHIP_AMBIGUOUS)
    val exit =
        child.onExit().thenApply<HostExit> { exited ->
            HostExit.OwnedChild(incarnation, exited.exitValue(), exitCause(state, incarnation))
        }
    return Refinement.Refined(ObservedHost.OwnedChild(incarnation, target, exit))
}

private fun adopt(
    incarnation: HostIncarnation,
    target: IdeProjectTarget,
    state: Path,
): Refinement<ObservedHost, HostIncarnationFailure> {
    val process =
        ProcessHandle.of(incarnation.pid).orElse(null)
            ?: return Refinement.Rejected(HostIncarnationFailure.PROCESS_UNAVAILABLE)
    if (!process.isAlive) return Refinement.Rejected(HostIncarnationFailure.PROCESS_UNAVAILABLE)
    if (process.startedMillis() != incarnation.processStartEpochMillis)
        return Refinement.Rejected(HostIncarnationFailure.START_MISMATCH)
    if (process.info().command().orElse(null) != incarnation.executable)
        return Refinement.Rejected(HostIncarnationFailure.EXECUTABLE_MISMATCH)
    val exit = process.onExit().thenApply<HostExit> { HostExit.Adopted(incarnation, exitCause(state, incarnation)) }
    return Refinement.Refined(ObservedHost.Adopted(incarnation, target, exit))
}

private fun ProcessHandle.startedMillis(): Long? = info().startInstant().orElse(null)?.toEpochMilli()

private fun exitCause(state: Path, incarnation: HostIncarnation): HostExitCause {
    val path = HostExitRecord.path(state)
    return try {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.getOwner(path, NOFOLLOW_LINKS) != Files.getOwner(state))
            return HostExitCause.Unknown
        val raw = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_EXIT_RECORD_BYTES + 1) }
        if (raw.size > MAXIMUM_EXIT_RECORD_BYTES) return HostExitCause.Unknown
        val record = Json.decodeFromString(HostExitRecord.serializer(), raw.decodeToString())
        if (record.schemaVersion != HostExitRecord.SCHEMA_VERSION || record.hostLifetime != incarnation.hostLifetime)
            HostExitCause.Unknown
        else HostExitCause.RequestedStop(record.reason, record.index)
    } catch (_: IOException) {
        HostExitCause.Unknown
    } catch (_: SecurityException) {
        HostExitCause.Unknown
    } catch (_: SerializationException) {
        HostExitCause.Unknown
    }
}

private const val MAXIMUM_EXIT_RECORD_BYTES = 4096
