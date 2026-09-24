package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.IdeProjectTarget
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@JvmInline
internal value class WorkspacePreparationId private constructor(val value: UUID) {
    companion object {
        fun fresh() = WorkspacePreparationId(UUID.randomUUID())

        fun admit(raw: String): WorkspacePreparationId? = canonicalPreparationUuid(raw)?.let(::WorkspacePreparationId)
    }
}

/** Retains the exact root and native incarnations established by lifecycle readiness. */
internal class PreparedWorkspace private constructor(val root: CanonicalRoot, val host: UUID, val project: UUID) {
    val target: IdeProjectTarget
        get() = IdeProjectTarget(host.toString(), project.toString(), root.path.toString())

    companion object {
        fun admit(
            root: CanonicalRoot,
            target: IdeProjectTarget,
        ): Refinement<PreparedWorkspace, WorkspacePreparationFailure> {
            val host = canonicalPreparationUuid(target.host)
            val project = canonicalPreparationUuid(target.project)
            return if (target.root == root.path.toString() && host != null && project != null)
                Refinement.Refined(PreparedWorkspace(root, host, project))
            else Refinement.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED)
        }
    }
}

@kotlinx.serialization.Serializable
enum class WorkspacePreparationFailure {
    CAPACITY_EXCEEDED,
    CLOSED,
    UNKNOWN_OPERATION,
    REQUEST_ID_MISMATCH,
    RESPONSE_REJECTED,
    DEADLINE_EXCEEDED,
    INTERRUPTED,
    IDENTITY_REJECTED,
    ROOT_REJECTED,
}

internal sealed interface WorkspacePreparationOutcome {
    sealed interface Terminal : WorkspacePreparationOutcome

    data class Pending(val stage: IdeLifecycleStage) : WorkspacePreparationOutcome

    data class Complete(val workspace: PreparedWorkspace) : WorkspacePreparationOutcome.Terminal

    data class Rejected(val failure: WorkspacePreparationFailure) : WorkspacePreparationOutcome.Terminal

    data class Blocked(val reason: IdeLifecycleFailure) : WorkspacePreparationOutcome.Terminal
}

internal class WorkspacePreparation internal constructor(val id: WorkspacePreparationId, val root: CanonicalRoot) {
    internal val mutable =
        MutableStateFlow<WorkspacePreparationOutcome>(WorkspacePreparationOutcome.Pending(IdeLifecycleStage.OPENING))
    val state: StateFlow<WorkspacePreparationOutcome>
        get() = mutable
}

internal fun canonicalPreparationUuid(raw: String): UUID? =
    try {
        UUID.fromString(raw).takeIf { it.toString() == raw }
    } catch (_: IllegalArgumentException) {
        null
    }

internal data class NativePreparationProgress(val host: UUID, val stage: IdeLifecycleStage)

internal fun admitPreparationProgress(
    pending: IdeLifecycleResult.Pending,
    id: WorkspacePreparationId,
    previous: NativePreparationProgress?,
): Refinement<NativePreparationProgress, WorkspacePreparationFailure> {
    fun reject() = Refinement.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED)
    if (pending.requestId != id.value.toString())
        return Refinement.Rejected(WorkspacePreparationFailure.REQUEST_ID_MISMATCH)
    val host = canonicalPreparationUuid(pending.host) ?: return reject()
    if (previous != null) {
        if (host != previous.host) return reject()
        if (pending.stage.ordinal < previous.stage.ordinal) return reject()
    }
    return when (pending.stage) {
        IdeLifecycleStage.OPENING,
        IdeLifecycleStage.IMPORTING,
        IdeLifecycleStage.ADMISSION -> Refinement.Refined(NativePreparationProgress(host, pending.stage))
        IdeLifecycleStage.PRESENTING,
        IdeLifecycleStage.CLOSING -> reject()
    }
}
