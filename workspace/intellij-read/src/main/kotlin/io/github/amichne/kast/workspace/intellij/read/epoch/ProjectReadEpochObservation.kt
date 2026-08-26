package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Epoch boundary whose root identities have already crossed their raw adapter boundaries. */
internal data class ProjectReadEpochBoundary(
    val projectModelRevision: ProjectReadEpochSignalSample,
    val projectRoot: ProjectEpochRootIdentity,
    val gradleRoot: GradleEpochRootIdentity,
    val lastImportTimestamp: Long,
    val lastSuccessfulImportTimestamp: Long,
    val psiModificationCount: ProjectReadEpochSignalSample,
    val rootFilteredVfsBatchCount: ProjectReadEpochSignalSample,
    val rootModelModificationCount: ProjectReadEpochSignalSample,
    val dumbModeModificationCount: ProjectReadEpochSignalSample,
    val dumb: Boolean,
)

/** A sampled signal value or its already-typed terminal failure. */
internal sealed interface ProjectReadEpochSignalSample {
    data class Value(val value: Long) : ProjectReadEpochSignalSample
    data class Rejected(
        val failure: ProjectReadEpochObservationFailure,
    ) : ProjectReadEpochSignalSample
}

/** Adapter-local projection of the bounded paths carried by one VFS event. */
internal sealed interface ProjectReadEpochVfsEvent {
    data class Change(val path: String) : ProjectReadEpochVfsEvent
    data class Move(val oldPath: String, val newPath: String) : ProjectReadEpochVfsEvent
    data class Rename(val oldPath: String, val newPath: String) : ProjectReadEpochVfsEvent
}

/** Closed pure result of refining one bounded VFS batch against the admitted root. */
internal sealed interface ProjectReadEpochVfsBatchObservation {
    data object OutsideRoot : ProjectReadEpochVfsBatchObservation
    data object TouchesRoot : ProjectReadEpochVfsBatchObservation
    data class Rejected(val failure: ProjectReadEpochObservationFailure) :
        ProjectReadEpochVfsBatchObservation
}

/** Exact canonical-root capability consumed by pure VFS containment checks. */
internal class ProjectReadEpochVfsRoot private constructor(private val path: Path) {
    fun contains(candidate: ProjectReadEpochVfsPath): Boolean = candidate.isWithin(path)

    companion object {
        /**
         * Proof transition: `CanonicalWorkspaceRoot -> ProjectReadEpochVfsRoot`.
         * Preserves the already-proven canonical root; raw Path extraction is permitted only here.
         */
        fun from(root: CanonicalWorkspaceRoot): ProjectReadEpochVfsRoot =
            ProjectReadEpochVfsRoot(Path.of(root.value))
    }
}

/** Bounded absolute-normalized VFS event path consumed only by root containment. */
internal class ProjectReadEpochVfsPath private constructor(private val path: Path) {
    internal fun isWithin(root: Path): Boolean = path.startsWith(root)

    companion object {
        /**
         * Proof transition: `String -> Refinement<ProjectReadEpochVfsPath,
         * ProjectReadEpochObservationFailure>`.
         * Establishes a bounded absolute normalized event path. Raw VFS path text may enter only
         * from the IntelliJ listener projection or portable tests at this adapter boundary.
         */
        fun admit(
            raw: String,
        ): Refinement<ProjectReadEpochVfsPath, ProjectReadEpochObservationFailure> {
            if (raw.isEmpty() || raw.length > PROJECT_READ_EPOCH_MAX_PATH_CHARACTERS ||
                raw.toByteArray(Charsets.UTF_8).size > PROJECT_READ_EPOCH_MAX_PATH_UTF8_BYTES
            ) return Refinement.Rejected(ProjectReadEpochObservationFailure.VfsPathMalformed)
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return Refinement.Rejected(ProjectReadEpochObservationFailure.VfsPathMalformed)
            }
            return if (path.isAbsolute && path.normalize() == path) {
                Refinement.Refined(ProjectReadEpochVfsPath(path))
            } else {
                Refinement.Rejected(ProjectReadEpochObservationFailure.VfsPathMalformed)
            }
        }
    }
}

/** One bounded metadata counter retained for the admitted Project/runtime lifetime. */
internal class ProjectReadEpochMetadataCounter {
    private val state = AtomicReference<ProjectReadEpochSignalSample>(
        ProjectReadEpochSignalSample.Value(0),
    )

    fun advance() {
        while (true) {
            when (val observed = state.get()) {
                is ProjectReadEpochSignalSample.Rejected -> return
                is ProjectReadEpochSignalSample.Value -> {
                    val next = if (observed.value == Long.MAX_VALUE) {
                        ProjectReadEpochSignalSample.Rejected(
                            ProjectReadEpochObservationFailure.SignalExhausted,
                        )
                    } else {
                        ProjectReadEpochSignalSample.Value(observed.value + 1)
                    }
                    if (state.compareAndSet(observed, next)) return
                }
            }
        }
    }

    fun reject(failure: ProjectReadEpochObservationFailure) {
        while (true) {
            val observed = state.get()
            if (observed is ProjectReadEpochSignalSample.Rejected) return
            if (state.compareAndSet(observed, ProjectReadEpochSignalSample.Rejected(failure))) {
                return
            }
        }
    }

    fun sample(): ProjectReadEpochSignalSample = state.get()
}

/**
 * Proof transition: `(ProjectReadEpochVfsRoot, List<ProjectReadEpochVfsEvent>) ->
 * ProjectReadEpochVfsBatchObservation`.
 *
 * Establishes whether at least one bounded event path is within the exact admitted root. An
 * oversized batch or malformed path is a closed rejection. Raw event strings may be extracted
 * only by the IntelliJ VFS listener; this projection performs no effect or semantic work.
 */
internal fun observeProjectReadEpochVfsBatch(
    root: ProjectReadEpochVfsRoot,
    events: List<ProjectReadEpochVfsEvent>,
): ProjectReadEpochVfsBatchObservation {
    if (events.size > PROJECT_READ_EPOCH_MAX_VFS_EVENTS_PER_BATCH) {
        return ProjectReadEpochVfsBatchObservation.Rejected(
            ProjectReadEpochObservationFailure.VfsBatchLimitExceeded,
        )
    }
    var touchesRoot = false
    for (event in events) {
        val rawPaths = when (event) {
            is ProjectReadEpochVfsEvent.Change -> listOf(event.path)
            is ProjectReadEpochVfsEvent.Move -> listOf(event.oldPath, event.newPath)
            is ProjectReadEpochVfsEvent.Rename -> listOf(event.oldPath, event.newPath)
        }
        for (raw in rawPaths) when (val refined = ProjectReadEpochVfsPath.admit(raw)) {
            is Refinement.Refined -> if (root.contains(refined.value)) touchesRoot = true
            is Refinement.Rejected -> return ProjectReadEpochVfsBatchObservation.Rejected(
                refined.failure,
            )
        }
    }
    return if (touchesRoot) {
        ProjectReadEpochVfsBatchObservation.TouchesRoot
    } else {
        ProjectReadEpochVfsBatchObservation.OutsideRoot
    }
}

/** Immutable adapter-private state retained inside one opaque `ProjectReadEpoch`. */
internal class ProjectReadEpochState private constructor(
    private val projectModelRevision: EpochSignalCount<ProjectModelSignal>,
    private val projectRoot: ProjectEpochRootIdentity,
    private val gradleRoot: GradleEpochRootIdentity,
    private val importState: EpochImportState,
    private val psiModificationCount: EpochSignalCount<PsiSignal>,
    private val rootFilteredVfsBatchCount: EpochSignalCount<VfsSignal>,
    private val rootModelModificationCount: EpochSignalCount<RootModelSignal>,
    private val dumbModeModificationCount: EpochSignalCount<DumbModeSignal>,
) {
    override fun equals(other: Any?): Boolean = other is ProjectReadEpochState &&
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
         * Establishes a smart, constant-size, immutable snapshot containing every KVP-015
         * movement signal. The finite expected failure is
         * [ProjectReadEpochObservationFailure]. Raw platform values may enter only from the live
         * IntelliJ observation adapter or portable contract fixtures in this module.
         */
        fun admit(
            boundary: ProjectReadEpochBoundary,
        ): Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure> {
            if (boundary.dumb) {
                return Refinement.Rejected(ProjectReadEpochObservationFailure.DumbMode)
            }
            val projectModel = when (
                val result = boundary.projectModelRevision.refineCount<ProjectModelSignal>()
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
            val importState = when (val result = EpochImportState.admit(
                boundary.lastImportTimestamp,
                boundary.lastSuccessfulImportTimestamp,
            )) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
            val psi = when (val result = boundary.psiModificationCount.refineCount<PsiSignal>()) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
            val vfs = when (
                val result = boundary.rootFilteredVfsBatchCount.refineCount<VfsSignal>()
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
            val rootModel = when (
                val result = boundary.rootModelModificationCount.refineCount<RootModelSignal>()
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return result
            }
            val dumbCycle = when (
                val result = boundary.dumbModeModificationCount.refineCount<DumbModeSignal>()
            ) {
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
                ),
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

private class EpochSignalCount<Authority : EpochSignalAuthority> private constructor(
    private val value: Long,
) {
    override fun equals(other: Any?): Boolean = other is EpochSignalCount<*> && value == other.value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        /**
         * Proof transition: `Long -> Refinement<EpochSignalCount<Authority>,
         * ProjectReadEpochObservationFailure>`.
         * Establishes a non-negative signal count. Raw counters enter only from the adapter
         * boundary; exhaustion is the closed expected failure.
         */
        fun <Authority : EpochSignalAuthority> admit(
            value: Long,
        ): Refinement<EpochSignalCount<Authority>, ProjectReadEpochObservationFailure> =
            if (value >= 0) {
                Refinement.Refined(EpochSignalCount<Authority>(value))
            } else {
                Refinement.Rejected(ProjectReadEpochObservationFailure.SignalExhausted)
            }
    }
}

private class EpochImportState private constructor(
    private val lastImportTimestamp: Long,
    private val lastSuccessfulImportTimestamp: Long,
) {
    override fun equals(other: Any?): Boolean = other is EpochImportState &&
        lastImportTimestamp == other.lastImportTimestamp &&
        lastSuccessfulImportTimestamp == other.lastSuccessfulImportTimestamp

    override fun hashCode(): Int =
        31 * lastImportTimestamp.hashCode() + lastSuccessfulImportTimestamp.hashCode()

    companion object {
        /**
         * Proof transition: `(Long, Long) -> Refinement<EpochImportState,
         * ProjectReadEpochObservationFailure>`.
         * Establishes coherent non-negative cached import timestamps. Raw counters enter only
         * from `ProjectReadEpochBoundary`; incoherence is the closed expected failure.
         */
        fun admit(
            lastImport: Long,
            lastSuccessful: Long,
        ): Refinement<EpochImportState, ProjectReadEpochObservationFailure> =
            if (lastImport >= 0 && lastSuccessful >= 0 && lastSuccessful <= lastImport) {
                Refinement.Refined(EpochImportState(lastImport, lastSuccessful))
            } else {
                Refinement.Rejected(
                    ProjectReadEpochObservationFailure.ImportTimestampsIncoherent,
                )
            }
    }
}

/**
 * Proof transition: `ProjectReadEpochSignalSample -> Refinement<EpochSignalCount<Authority>,
 * ProjectReadEpochObservationFailure>`. Establishes one category-branded non-negative count.
 * Raw platform counters enter only through `ProjectReadEpochBoundary`; a rejected sample or
 * exhaustion remains the closed [ProjectReadEpochObservationFailure] set.
 */
private fun <Authority : EpochSignalAuthority> ProjectReadEpochSignalSample.refineCount(): Refinement<
    EpochSignalCount<Authority>,
    ProjectReadEpochObservationFailure,
> = when (this) {
    is ProjectReadEpochSignalSample.Rejected -> Refinement.Rejected(failure)
    is ProjectReadEpochSignalSample.Value -> EpochSignalCount.admit<Authority>(value)
}
internal const val PROJECT_READ_EPOCH_MAX_VFS_EVENTS_PER_BATCH = 4_096
internal const val PROJECT_READ_EPOCH_MAX_PATH_CHARACTERS = 4_096
internal const val PROJECT_READ_EPOCH_MAX_PATH_UTF8_BYTES = 8_192
