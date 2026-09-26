package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** The facts that produced this row at its latest relation or set boundary. Earlier facts remain in connections. */
sealed interface QueryArrivalEvidence {
    data object None : QueryArrivalEvidence

    data class Proven private constructor(val facts: List<RelationFact>) : QueryArrivalEvidence {
        companion object {
            fun one(fact: RelationFact): Proven = Proven(listOf(fact))

            fun merge(first: Proven, second: Proven): Proven =
                Proven(java.util.Collections.unmodifiableList((first.facts + second.facts).distinct().sorted()))
        }
    }
}

fun QueryArrivalEvidence.merge(other: QueryArrivalEvidence): QueryArrivalEvidence =
    when {
        this is QueryArrivalEvidence.Proven && other is QueryArrivalEvidence.Proven ->
            QueryArrivalEvidence.Proven.merge(this, other)
        this is QueryArrivalEvidence.Proven -> this
        else -> other
    }

enum class QueryRelationOmissionFailure {
    PROVIDER_MISMATCH
}

/** A provider's bounded omission evidence stays associated with the exact queried subject and meaning. */
data class QueryRelationOmission
private constructor(
    val subject: SymbolSelector,
    val meaning: RelationMeaning,
    val evidence: RelationOmissionEvidence,
) {
    companion object {
        fun create(
            subject: SymbolSelector,
            meaning: RelationMeaning,
            evidence: RelationOmissionEvidence,
        ): Refinement<QueryRelationOmission, QueryRelationOmissionFailure> =
            if (evidence.provider == RelationProviderKind.forMeaning(meaning)) {
                Refinement.Refined(QueryRelationOmission(subject, meaning, evidence))
            } else {
                Refinement.Rejected(QueryRelationOmissionFailure.PROVIDER_MISMATCH)
            }
    }
}
