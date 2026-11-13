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

/**
 * Imported-model ownership of one VFS event endpoint, observed by the IntelliJ adapter under platform read access.
 *
 * Only [UNOWNED] can make an endpoint irrelevant. It requires an existing regular file that the live project file index
 * places outside every source, generated, resource, test, and library root.
 */
internal enum class ProjectReadEpochVfsOwnership {
    UNOWNED,
    ADMITTED,
    /** Directory, missing, invalid, or unobservable endpoint; never proof of irrelevance. */
    UNKNOWN,
}

/** One bounded event endpoint; [ownership] is observed only when the endpoint lies inside the admitted root. */
internal class ProjectReadEpochVfsEndpoint(val path: String, val ownership: ProjectReadEpochVfsOwnership)

/** Adapter-local projection of one VFS event: every prior and resulting endpoint it can affect. */
internal class ProjectReadEpochVfsEvent(val endpoints: List<ProjectReadEpochVfsEndpoint>)

/** Closed pure result of refining one bounded VFS batch against the admitted root and imported ownership. */
internal sealed interface ProjectReadEpochVfsBatchObservation {
    /** Every endpoint is outside the admitted root or an [ProjectReadEpochVfsOwnership.UNOWNED] file. */
    data object Irrelevant : ProjectReadEpochVfsBatchObservation

    data object Relevant : ProjectReadEpochVfsBatchObservation

    /** Capacity or ownership evidence prevents classification; this is not relevance or irrelevance proof. */
    data object RelevanceUnknown : ProjectReadEpochVfsBatchObservation

    data class Rejected(val failure: ProjectReadEpochObservationFailure) : ProjectReadEpochVfsBatchObservation
}

/** Exact canonical-root capability consumed by pure VFS containment checks. */
internal class ProjectReadEpochVfsRoot private constructor(private val path: Path) {
    fun contains(candidate: ProjectReadEpochVfsPath): Boolean = candidate.isWithin(path)

    fun hasAncestor(candidate: ProjectReadEpochVfsPath): Boolean = candidate.isAncestorOf(path)

    /** True only for a well-formed bounded path inside this root; malformed paths are rejected by batch refinement. */
    fun admits(raw: String, limits: ReadLimits = ReadLimits.Default): Boolean =
        when (val refined = ProjectReadEpochVfsPath.admit(raw, limits)) {
            is Refinement.Refined -> contains(refined.value)
            is Refinement.Rejected -> false
        }

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

    internal fun isAncestorOf(root: Path): Boolean = root.startsWith(path)

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
 * Establishes [ProjectReadEpochVfsBatchObservation.Irrelevant] only when every bounded endpoint is outside the exact
 * admitted root or an observed unowned file. The adapter observes ownership only for in-root endpoints. Changes to an
 * ancestor of the workspace remain relevant; every path is admitted, so a malformed suffix remains a closed rejection.
 * An oversized batch or unknown ownership retains unknown relevance and requires conservative invalidation. Raw event
 * strings may be extracted only by the IntelliJ VFS listener; this projection performs no semantic work.
 */
internal fun observeProjectReadEpochVfsBatch(
    root: ProjectReadEpochVfsRoot,
    events: List<ProjectReadEpochVfsEvent>,
    limits: ReadLimits = ReadLimits.Default,
): ProjectReadEpochVfsBatchObservation {
    if (events.size > limits[ReadLimitParameter.EPOCH_VFS_EVENTS].value) {
        return ProjectReadEpochVfsBatchObservation.RelevanceUnknown
    }
    var relevant = false
    var unknown = false
    for (event in events) {
        if (event.endpoints.isEmpty()) unknown = true
        for (endpoint in event.endpoints) {
            when (val refined = ProjectReadEpochVfsPath.admit(endpoint.path, limits)) {
                is Refinement.Rejected -> return ProjectReadEpochVfsBatchObservation.Rejected(refined.failure)
                is Refinement.Refined ->
                    if (root.hasAncestor(refined.value)) {
                        relevant = true
                    } else if (root.contains(refined.value)) {
                        when (endpoint.ownership) {
                            ProjectReadEpochVfsOwnership.UNOWNED -> Unit
                            ProjectReadEpochVfsOwnership.ADMITTED -> relevant = true
                            ProjectReadEpochVfsOwnership.UNKNOWN -> unknown = true
                        }
                    }
            }
        }
    }
    return when {
        relevant -> ProjectReadEpochVfsBatchObservation.Relevant
        unknown -> ProjectReadEpochVfsBatchObservation.RelevanceUnknown
        else -> ProjectReadEpochVfsBatchObservation.Irrelevant
    }
}

internal const val PROJECT_READ_EPOCH_MAX_VFS_EVENTS_PER_BATCH = 4_096
internal const val PROJECT_READ_EPOCH_MAX_PATH_CHARACTERS = 4_096
internal const val PROJECT_READ_EPOCH_MAX_PATH_UTF8_BYTES = 8_192
