package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
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

    data class Rejected(val failure: ProjectReadEpochObservationFailure) : ProjectReadEpochSignalSample
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

    /** Batch capacity prevents classification; this is neither root relevance nor exhaustive observation proof. */
    data object RelevanceUnknown : ProjectReadEpochVfsBatchObservation

    data class Rejected(val failure: ProjectReadEpochObservationFailure) : ProjectReadEpochVfsBatchObservation
}

/** Exact canonical-root capability consumed by pure VFS containment checks. */
internal class ProjectReadEpochVfsRoot private constructor(private val path: Path) {
    fun contains(candidate: ProjectReadEpochVfsPath): Boolean = candidate.isWithin(path)

    companion object {
        /**
         * Proof transition: `CanonicalWorkspaceRoot -> ProjectReadEpochVfsRoot`. Preserves the already-proven canonical
         * root; raw Path extraction is permitted only here.
         */
        fun from(root: CanonicalWorkspaceRoot): ProjectReadEpochVfsRoot = ProjectReadEpochVfsRoot(Path.of(root.value))
    }
}

/** Bounded absolute-normalized VFS event path consumed only by root containment. */
internal class ProjectReadEpochVfsPath private constructor(private val path: Path) {
    internal fun isWithin(root: Path): Boolean = path.startsWith(root)

    companion object {
        /**
         * Proof transition: `String -> Refinement<ProjectReadEpochVfsPath, ProjectReadEpochObservationFailure>`.
         * Establishes a bounded absolute normalized event path. Raw VFS path text may enter only from the IntelliJ
         * listener projection or portable tests at this adapter boundary.
         */
        fun admit(
            raw: String,
            limits: ReadLimits = ReadLimits.Default,
        ): Refinement<ProjectReadEpochVfsPath, ProjectReadEpochObservationFailure> {
            if (
                raw.isEmpty() ||
                    raw.length > limits[ReadLimitParameter.EPOCH_PATH_CHARACTERS].value ||
                    raw.toByteArray(Charsets.UTF_8).size > limits[ReadLimitParameter.EPOCH_PATH_BYTES].value
            )
                return Refinement.Rejected(ProjectReadEpochObservationFailure.VfsPathMalformed)
            val path =
                try {
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
    private val state = AtomicReference<ProjectReadEpochSignalSample>(ProjectReadEpochSignalSample.Value(0))

    fun advance() {
        while (true) {
            when (val observed = state.get()) {
                is ProjectReadEpochSignalSample.Rejected -> return
                is ProjectReadEpochSignalSample.Value -> {
                    val next =
                        if (observed.value == Long.MAX_VALUE) {
                            ProjectReadEpochSignalSample.Rejected(ProjectReadEpochObservationFailure.SignalExhausted)
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
 * Proof transition: `(ProjectReadEpochVfsRoot, List<ProjectReadEpochVfsEvent>) -> ProjectReadEpochVfsBatchObservation`.
 *
 * Establishes whether at least one bounded event path is within the exact admitted root. An oversized batch retains
 * unknown relevance and requires conservative invalidation; a malformed bounded path is a closed rejection. Raw event
 * strings may be extracted only by the IntelliJ VFS listener; this projection performs no effect or semantic work.
 */
internal fun observeProjectReadEpochVfsBatch(
    root: ProjectReadEpochVfsRoot,
    events: List<ProjectReadEpochVfsEvent>,
    limits: ReadLimits = ReadLimits.Default,
): ProjectReadEpochVfsBatchObservation {
    if (events.size > limits[ReadLimitParameter.EPOCH_VFS_EVENTS].value) {
        return ProjectReadEpochVfsBatchObservation.RelevanceUnknown
    }
    var touchesRoot = false
    for (event in events) {
        val rawPaths =
            when (event) {
                is ProjectReadEpochVfsEvent.Change -> listOf(event.path)
                is ProjectReadEpochVfsEvent.Move -> listOf(event.oldPath, event.newPath)
                is ProjectReadEpochVfsEvent.Rename -> listOf(event.oldPath, event.newPath)
            }
        for (raw in rawPaths) when (val refined = ProjectReadEpochVfsPath.admit(raw, limits)) {
            is Refinement.Refined -> if (root.contains(refined.value)) touchesRoot = true
            is Refinement.Rejected -> return ProjectReadEpochVfsBatchObservation.Rejected(refined.failure)
        }
    }
    return if (touchesRoot) {
        ProjectReadEpochVfsBatchObservation.TouchesRoot
    } else {
        ProjectReadEpochVfsBatchObservation.OutsideRoot
    }
}

internal const val PROJECT_READ_EPOCH_MAX_VFS_EVENTS_PER_BATCH = 4_096
internal const val PROJECT_READ_EPOCH_MAX_PATH_CHARACTERS = 4_096
internal const val PROJECT_READ_EPOCH_MAX_PATH_UTF8_BYTES = 8_192
