package io.github.amichne.kast.runtime.ide.read.revalidation

import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation

/** Exact observation phase retained by a typed epoch failure. */
enum class EpochRevalidationPhase { BEFORE, AFTER }

/**
 * Nominal proof that one value was projected before the accepting AFTER observation.
 *
 * Construction remains internal to the permit-scoped revalidation transition. The raw value may
 * be extracted only by the later operation adapter that consumes the completed read.
 */
class DetachedIdeReadProjection<out Value : Any> private constructor(
    val value: Value,
) {
    internal companion object {
        /** `Value -> DetachedIdeReadProjection<Value>` at the live Project read boundary. */
        fun <Value : Any> capture(value: Value): DetachedIdeReadProjection<Value> =
            DetachedIdeReadProjection(value)
    }
}

/** Closed result accepted only after one same-source BEFORE/AFTER epoch comparison. */
sealed interface RevalidatedIdeReadResult<out Value : Any> {
    data class Complete<Value : Any>(
        val projection: DetachedIdeReadProjection<Value>,
    ) : RevalidatedIdeReadResult<Value>

    sealed interface Rejected : RevalidatedIdeReadResult<Nothing> {
        data object WorkspaceMoved : Rejected
        data object IncomparableEpoch : Rejected
        data class EpochObservationRejected(
            val phase: EpochRevalidationPhase,
            val failure: ProjectReadEpochObservationFailure,
        ) : Rejected
    }
}

/**
 * Proof transition: `(ProjectReadEpoch<*>, DetachedIdeReadProjection<Value>,
 * ProjectReadEpochObservation) ->
 * RevalidatedIdeReadResult<Value>`.
 *
 * Establishes `Complete` only for exact retained-source equality after projection. Movement,
 * incomparable source identity, and AFTER observation failure remain closed typed outcomes.
 */
internal fun <Value : Any> revalidateIdeRead(
    before: ProjectReadEpoch<*>,
    projection: DetachedIdeReadProjection<Value>,
    after: ProjectReadEpochObservation,
): RevalidatedIdeReadResult<Value> = when (after) {
    is ProjectReadEpochObservation.Rejected ->
        RevalidatedIdeReadResult.Rejected.EpochObservationRejected(
            EpochRevalidationPhase.AFTER,
            after.failure,
        )
    is ProjectReadEpochObservation.Observed -> when (before.relationTo(after.epoch)) {
        ProjectReadEpochRelation.SAME -> RevalidatedIdeReadResult.Complete(projection)
        ProjectReadEpochRelation.MOVED -> RevalidatedIdeReadResult.Rejected.WorkspaceMoved
        ProjectReadEpochRelation.INCOMPARABLE ->
            RevalidatedIdeReadResult.Rejected.IncomparableEpoch
    }
}
