package io.github.amichne.kast.traversal.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationKnownMinimum
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationReadResult

/** Disposition of unenumerated neighbors; neither variant estimates omitted subtrees. */
enum class TraversalExpansionRemainder {
    CONTINUATION_RETAINED,
    NOT_EXPLORED,
}

/** The retained observation's original subject, measurement and samples all bind its resume integrity. */
internal fun StringBuilder.appendRetainedExpansion(expansion: TraversalPartialExpansion) {
    appendTraversalField(expansion.entry.node.fingerprint.value)
    appendTraversalField(expansion.entry.depth.value.toString())
    appendTraversalField(expansion.knownMinimum.value.toString())
    appendTraversalField(expansion.remainder.name)
    expansion.omissions.forEach { evidence ->
        appendTraversalField(evidence.provider.name)
        appendTraversalField(evidence.reason.name)
        appendTraversalField(evidence.measurement.toString())
        appendTraversalField(evidence.samples.retention.name)
        evidence.samples.locations.forEach { sample ->
            appendTraversalField(sample.file.stableValue)
            appendTraversalField(sample.range.startInclusive.toString())
            appendTraversalField(sample.range.endExclusive.toString())
        }
    }
}

enum class TraversalPartialExpansionFailure {
    EMPTY_LIMITATIONS,
    ENTRY_OUTSIDE_PLAN,
    SUBJECT_MISMATCH,
    INCONSISTENT_OMISSIONS,
}

/** One qualified relation read on this page, retaining the subject and its depth (root depth is zero). */
@ConsistentCopyVisibility
data class TraversalPartialExpansion
private constructor(
    val entry: TraversalFrontierEntry,
    val knownMinimum: RelationKnownMinimum,
    val omissions: List<RelationOmissionEvidence>,
    val remainder: TraversalExpansionRemainder,
) {
    val limitations: Set<RelationLimitation>
        get() = omissions.mapTo(linkedSetOf()) { it.reason }

    /** Retain observed omitted work; a transient unmeasured page boundary may clear after its successor. */
    fun retainedExpansions(): List<TraversalPartialExpansion> {
        val resumableBoundaries =
            setOf(
                RelationLimitation.RESULT_LIMIT_REACHED,
                RelationLimitation.BYTE_LIMIT_REACHED,
                RelationLimitation.WORK_LIMIT_REACHED,
                RelationLimitation.TIME_LIMIT_REACHED,
                RelationLimitation.PROVIDER_INCOMPLETE,
            )
        val retained = omissions.filter {
            remainder == TraversalExpansionRemainder.NOT_EXPLORED ||
                it.reason !in resumableBoundaries ||
                it.measurement is RelationOmissionMeasurement.ObservedOnPage ||
                it.samples.locations.isNotEmpty()
        }
        return if (retained.isEmpty()) emptyList()
        else listOf(copy(omissions = java.util.Collections.unmodifiableList(retained)))
    }

    companion object {
        fun create(
            plan: TraversalPlan,
            entry: TraversalFrontierEntry,
            result: RelationReadResult.Qualified,
            remainder: TraversalExpansionRemainder,
        ): Refinement<TraversalPartialExpansion, TraversalPartialExpansionFailure> {
            val limitations = result.coverage.limitations
            when {
                limitations.isEmpty() -> return Refinement.Rejected(TraversalPartialExpansionFailure.EMPTY_LIMITATIONS)
                entry.node.endpoint.lease != plan.start.lease ||
                    !plan.admitsEndpoint(entry.node.endpoint) ||
                    entry.depth.value >= plan.budget.depth.value ->
                    return Refinement.Rejected(TraversalPartialExpansionFailure.ENTRY_OUTSIDE_PLAN)
                result.batch.request.subject.fingerprint != entry.node.fingerprint ->
                    return Refinement.Rejected(TraversalPartialExpansionFailure.SUBJECT_MISMATCH)
                result.batch.omissions.any { it.reason !in limitations } ->
                    return Refinement.Rejected(TraversalPartialExpansionFailure.INCONSISTENT_OMISSIONS)
                else -> Unit
            }
            val recorded = result.batch.omissions.associateBy { it.reason }
            val omissions =
                limitations
                    .sortedBy { it.ordinal }
                    .map { reason ->
                        recorded[reason]
                            ?: RelationOmissionEvidence.unmeasured(
                                result.batch.request.providerCursor.provider,
                                reason,
                            )
                    }
            return Refinement.Refined(
                TraversalPartialExpansion(
                    entry,
                    result.coverage.knownMinimum,
                    java.util.Collections.unmodifiableList(omissions),
                    remainder,
                )
            )
        }
    }
}
