package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.source.contract.DeclarationVisibility

class QueryVisibilitySelection private constructor(val values: List<DeclarationVisibility>) {
    companion object {
        fun from(raw: Set<DeclarationVisibility>): Refinement<QueryVisibilitySelection, QueryCollectionFailure> =
            if (raw.isEmpty()) {
                Refinement.Rejected(QueryCollectionFailure.EMPTY)
            } else {
                Refinement.Refined(QueryVisibilitySelection(raw.sortedBy { it.ordinal }))
            }
    }

    override fun equals(other: Any?): Boolean = other is QueryVisibilitySelection && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

sealed interface QueryPredicate {
    data class Visibility(val values: QueryVisibilitySelection) : QueryPredicate

    data class Primitive(
        val field: QueryPrimitiveField,
        val operator: QueryPrimitiveOperator,
        val value: QueryPrimitiveValue,
    ) : QueryPredicate
}

enum class QueryPrimitiveValueFailure {
    BLANK,
    TOO_LONG,
}

@JvmInline
value class QueryPrimitiveValue private constructor(val text: String) {
    companion object {
        private const val MAX_LENGTH = 512

        fun parse(raw: String): Refinement<QueryPrimitiveValue, QueryPrimitiveValueFailure> =
            when {
                raw.isBlank() -> Refinement.Rejected(QueryPrimitiveValueFailure.BLANK)
                raw.length > MAX_LENGTH -> Refinement.Rejected(QueryPrimitiveValueFailure.TOO_LONG)
                else -> Refinement.Refined(QueryPrimitiveValue(raw))
            }
    }
}

enum class QueryPrimitiveField {
    NAME,
    KIND,
    FILE,
}

enum class QueryPrimitiveOperator {
    EQUALS,
    NOT_EQUALS,
    STARTS_WITH,
    ENDS_WITH,
}

sealed interface QueryStepSyntax {
    data class Where(val predicate: QueryPredicate) : QueryStepSyntax

    data class Related(val meaning: RelationMeaning) : QueryStepSyntax

    data class AppendReferences(val references: QueryExactReferences) : QueryStepSyntax

    data object Distinct : QueryStepSyntax
}
