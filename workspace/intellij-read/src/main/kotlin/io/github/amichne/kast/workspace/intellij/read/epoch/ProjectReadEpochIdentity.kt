package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Closed exact relation between the admitted Project root and one selected Gradle root. */
internal enum class ProjectGradleRootRelation { SAME, MOVED }

/** Exact normalized project-root identity admitted before cached Gradle-model lookup. */
internal class ProjectEpochRootIdentity private constructor(
    private val key: EpochRootComparisonKey,
) {
    /**
     * Proof transition: `(ProjectEpochRootIdentity, GradleEpochRootIdentity) ->
     * ProjectGradleRootRelation`. Compares only canonical strong identities; raw text never exits.
     */
    fun relationTo(candidate: GradleEpochRootIdentity): ProjectGradleRootRelation =
        candidate.relationTo(key)

    override fun equals(other: Any?): Boolean =
        other is ProjectEpochRootIdentity && key == other.key

    override fun hashCode(): Int = key.hashCode()

    companion object {
        /**
         * Proof transition: `String? -> Refinement<ProjectEpochRootIdentity,
         * ProjectReadEpochObservationFailure>`.
         * Establishes a present, bounded, absolute, normalized project root before it enters
         * Gradle-model selection. Raw root text may enter only from the live Project adapter.
         */
        fun admit(
            raw: String?,
        ): Refinement<ProjectEpochRootIdentity, ProjectReadEpochObservationFailure> =
            EpochRootComparisonKey.refine(
                raw,
                ProjectReadEpochObservationFailure.ProjectRootUnavailable,
                ProjectReadEpochObservationFailure.ProjectRootMalformed,
                ::ProjectEpochRootIdentity,
            )
    }
}

/** Exact normalized root identity returned by the selected cached Gradle model. */
internal class GradleEpochRootIdentity private constructor(
    private val key: EpochRootComparisonKey,
) {
    /**
     * Proof transition: `(GradleEpochRootIdentity, EpochRootComparisonKey) ->
     * ProjectGradleRootRelation`. Establishes equality or movement from canonical strong keys.
     */
    internal fun relationTo(projectRoot: EpochRootComparisonKey): ProjectGradleRootRelation =
        if (key == projectRoot) ProjectGradleRootRelation.SAME else ProjectGradleRootRelation.MOVED

    override fun equals(other: Any?): Boolean =
        other is GradleEpochRootIdentity && key == other.key

    override fun hashCode(): Int = key.hashCode()

    companion object {
        /**
         * Proof transition: `String? -> Refinement<GradleEpochRootIdentity,
         * ProjectReadEpochObservationFailure>`.
         * Establishes a present, bounded, absolute, normalized selected Gradle root. Raw root
         * text may enter only from the selected cached Gradle-model adapter.
         */
        fun admit(
            raw: String?,
        ): Refinement<GradleEpochRootIdentity, ProjectReadEpochObservationFailure> =
            EpochRootComparisonKey.refine(
                raw,
                ProjectReadEpochObservationFailure.GradleRootUnavailable,
                ProjectReadEpochObservationFailure.GradleRootMalformed,
                ::GradleEpochRootIdentity,
            )
    }
}

/** Strong selected Gradle-root identity with the cached import movement signals. */
internal data class ObservedEpochGradleModel(
    val root: GradleEpochRootIdentity,
    val lastImportTimestamp: Long,
    val lastSuccessfulImportTimestamp: Long,
)

/** Canonical root value shared only for closed cross-authority comparison. */
internal class EpochRootComparisonKey private constructor(private val value: String) {
    override fun equals(other: Any?): Boolean =
        other is EpochRootComparisonKey && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /**
         * Proof transition: `String? -> Refinement<Root,
         * ProjectReadEpochObservationFailure>`.
         * Establishes the bounded absolute-normalized root invariant before the supplied private
         * constructor wraps its canonical comparison key. Raw Path use is confined here.
         */
        internal fun <Root : Any> refine(
            raw: String?,
            unavailable: ProjectReadEpochObservationFailure,
            malformed: ProjectReadEpochObservationFailure,
            construct: (EpochRootComparisonKey) -> Root,
        ): Refinement<Root, ProjectReadEpochObservationFailure> {
            if (raw.isNullOrEmpty()) return Refinement.Rejected(unavailable)
            if (raw.length > MAX_ROOT_CHARACTERS ||
                raw.toByteArray(Charsets.UTF_8).size > MAX_ROOT_UTF8_BYTES
            ) return Refinement.Rejected(malformed)
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return Refinement.Rejected(malformed)
            }
            if (!path.isAbsolute || path.normalize() != path) {
                return Refinement.Rejected(malformed)
            }
            return Refinement.Refined(construct(EpochRootComparisonKey(path.toString())))
        }
    }
}

private const val MAX_ROOT_CHARACTERS = 4_096
private const val MAX_ROOT_UTF8_BYTES = 8_192
