package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure

/** Immutable adapter-private state retained inside one opaque `ProjectReadEpoch`. */
internal class ProjectReadEpochState
private constructor(
    private val projectModelRevision: EpochSignalCount<ProjectModelSignal>,
    private val projectRoot: ProjectEpochRootIdentity,
    private val gradleRoot: GradleEpochRootIdentity,
    private val importState: EpochImportState,
    private val psiModificationCount: EpochSignalCount<PsiSignal>,
    private val rootFilteredVfsBatchCount: EpochSignalCount<VfsSignal>,
    private val rootModelModificationCount: EpochSignalCount<RootModelSignal>,
    private val dumbModeModificationCount: EpochSignalCount<DumbModeSignal>,
) {
    /**
     * Detached epoch environment signals; compiler settings, external inputs and source inventories need revalidation.
     */
    fun environment(): Environment = Environment.from(this)

    class Environment private constructor(private val state: ProjectReadEpochState) {
        override fun equals(other: Any?): Boolean =
            other is Environment &&
                state.projectModelRevision == other.state.projectModelRevision &&
                state.projectRoot == other.state.projectRoot &&
                state.gradleRoot == other.state.gradleRoot &&
                state.importState == other.state.importState &&
                state.rootModelModificationCount == other.state.rootModelModificationCount &&
                state.dumbModeModificationCount == other.state.dumbModeModificationCount

        override fun hashCode(): Int {
            var result = state.projectModelRevision.hashCode()
            result = 31 * result + state.projectRoot.hashCode()
            result = 31 * result + state.gradleRoot.hashCode()
            result = 31 * result + state.importState.hashCode()
            result = 31 * result + state.rootModelModificationCount.hashCode()
            return 31 * result + state.dumbModeModificationCount.hashCode()
        }

        companion object {
            fun from(state: ProjectReadEpochState): Environment = Environment(state)
        }
    }

    /** Finite causes only; identities, counters and import timestamps remain private. */
    fun changedSignalsFrom(previous: ProjectReadEpochState): Set<ProjectReadEpochSignal> {
        val signals = linkedSetOf<ProjectReadEpochSignal>()
        if (projectModelRevision != previous.projectModelRevision) signals += ProjectReadEpochSignal.WORKSPACE_MODEL
        if (projectRoot != previous.projectRoot) signals += ProjectReadEpochSignal.PROJECT_ROOT
        if (gradleRoot != previous.gradleRoot) signals += ProjectReadEpochSignal.GRADLE_ROOT
        if (importState != previous.importState) signals += ProjectReadEpochSignal.IMPORT_STATE
        if (psiModificationCount != previous.psiModificationCount) signals += ProjectReadEpochSignal.PSI
        if (rootFilteredVfsBatchCount != previous.rootFilteredVfsBatchCount) signals += ProjectReadEpochSignal.VFS
        if (rootModelModificationCount != previous.rootModelModificationCount)
            signals += ProjectReadEpochSignal.ROOT_MODEL
        if (dumbModeModificationCount != previous.dumbModeModificationCount) signals += ProjectReadEpochSignal.INDEXING
        return signals
    }

    override fun equals(other: Any?): Boolean =
        other is ProjectReadEpochState &&
            projectModelRevision == other.projectModelRevision &&
            projectRoot == other.projectRoot &&
            gradleRoot == other.gradleRoot &&
            importState == other.importState &&
            psiModificationCount == other.psiModificationCount &&
            rootFilteredVfsBatchCount == other.rootFilteredVfsBatchCount &&
            rootModelModificationCount == other.rootModelModificationCount &&
            dumbModeModificationCount == other.dumbModeModificationCount

    override fun hashCode(): Int {
        var result = projectModelRevision.hashCode()
        result = 31 * result + projectRoot.hashCode()
        result = 31 * result + gradleRoot.hashCode()
        result = 31 * result + importState.hashCode()
        result = 31 * result + psiModificationCount.hashCode()
        result = 31 * result + rootFilteredVfsBatchCount.hashCode()
        result = 31 * result + rootModelModificationCount.hashCode()
        return 31 * result + dumbModeModificationCount.hashCode()
    }

    companion object {
        /**
         * Proof transition: `ProjectReadEpochBoundary -> Refinement<ProjectReadEpochState,
         * ProjectReadEpochObservationFailure>`.
         *
         * Establishes a smart, constant-size, immutable snapshot containing every epoch-signal policy movement signal.
         * The finite expected failure is [ProjectReadEpochObservationFailure]. Raw platform values may enter only from
         * the live IntelliJ observation adapter or portable contract fixtures in this module.
         */
        fun admit(
            boundary: ProjectReadEpochBoundary
        ): Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure> {
            if (boundary.dumb) {
                return Refinement.Rejected(ProjectReadEpochObservationFailure.DumbMode)
            }
            val projectModel =
                when (val result = boundary.projectModelRevision.refineCount<ProjectModelSignal>()) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            val importState =
                when (val result = boundary.refineImportState()) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            val psi =
                when (val result = boundary.psiModificationCount.refineCount<PsiSignal>()) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            val vfs =
                when (val result = boundary.rootFilteredVfsBatchCount.refineCount<VfsSignal>()) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            val rootModel =
                when (val result = boundary.rootModelModificationCount.refineCount<RootModelSignal>()) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            val dumbCycle =
                when (val result = boundary.dumbModeModificationCount.refineCount<DumbModeSignal>()) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            return Refinement.Refined(
                ProjectReadEpochState(
                    projectModel,
                    boundary.projectRoot,
                    boundary.gradleRoot,
                    importState,
                    psi,
                    vfs,
                    rootModel,
                    dumbCycle,
                )
            )
        }
    }
}

private sealed interface EpochSignalAuthority

private data object ProjectModelSignal : EpochSignalAuthority

private data object PsiSignal : EpochSignalAuthority

private data object VfsSignal : EpochSignalAuthority

private data object RootModelSignal : EpochSignalAuthority

private data object DumbModeSignal : EpochSignalAuthority

private class EpochSignalCount<Authority : EpochSignalAuthority> private constructor(private val value: Long) {
    override fun equals(other: Any?): Boolean = other is EpochSignalCount<*> && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /**
         * Proof transition: `Long -> Refinement<EpochSignalCount<Authority>, ProjectReadEpochObservationFailure>`.
         * Establishes a non-negative signal count. Raw counters enter only from the adapter boundary; exhaustion is the
         * closed expected failure.
         */
        fun <Authority : EpochSignalAuthority> admit(
            value: Long
        ): Refinement<EpochSignalCount<Authority>, ProjectReadEpochObservationFailure> =
            if (value >= 0) {
                Refinement.Refined(EpochSignalCount<Authority>(value))
            } else {
                Refinement.Rejected(ProjectReadEpochObservationFailure.SignalExhausted)
            }
    }
}

private class EpochImportState
private constructor(
    private val lastImportTimestamp: Long,
    private val lastSuccessfulImportTimestamp: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is EpochImportState &&
            lastImportTimestamp == other.lastImportTimestamp &&
            lastSuccessfulImportTimestamp == other.lastSuccessfulImportTimestamp

    override fun hashCode(): Int = 31 * lastImportTimestamp.hashCode() + lastSuccessfulImportTimestamp.hashCode()

    companion object {
        /**
         * Proof transition: `(Long, Long) -> Refinement<EpochImportState, ProjectReadEpochObservationFailure>`.
         * Establishes coherent non-negative cached import timestamps. Raw counters enter only from
         * `ProjectReadEpochBoundary`; incoherence is the closed expected failure.
         */
        fun admit(
            lastImport: Long,
            lastSuccessful: Long,
        ): Refinement<EpochImportState, ProjectReadEpochObservationFailure> =
            if (lastImport >= 0 && lastSuccessful >= 0 && lastSuccessful <= lastImport) {
                Refinement.Refined(EpochImportState(lastImport, lastSuccessful))
            } else {
                Refinement.Rejected(ProjectReadEpochObservationFailure.ImportTimestampsIncoherent)
            }
    }
}

/**
 * Proof transition: `ProjectReadEpochSignalSample -> Refinement<EpochSignalCount<Authority>,
 * ProjectReadEpochObservationFailure>`. Establishes one category-branded non-negative count. Raw platform counters
 * enter only through `ProjectReadEpochBoundary`; a rejected sample or exhaustion remains the closed
 * [ProjectReadEpochObservationFailure] set.
 */
private fun <Authority : EpochSignalAuthority> ProjectReadEpochSignalSample.refineCount():
    Refinement<
        EpochSignalCount<Authority>,
        ProjectReadEpochObservationFailure,
    > =
    when (this) {
        is ProjectReadEpochSignalSample.Rejected -> Refinement.Rejected(failure)
        is ProjectReadEpochSignalSample.Value -> EpochSignalCount.admit<Authority>(value)
    }

private fun ProjectReadEpochBoundary.refineImportState():
    Refinement<EpochImportState, ProjectReadEpochObservationFailure> =
    EpochImportState.admit(lastImportTimestamp, lastSuccessfulImportTimestamp)
