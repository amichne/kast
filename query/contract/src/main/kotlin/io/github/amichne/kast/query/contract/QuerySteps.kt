package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalStrategy

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
    data class ProjectBinding(val name: QueryBindingName) : QueryStepSyntax

    data class Where(val predicate: QueryPredicate) : QueryStepSyntax

    data class Related(val meaning: RelationMeaning) : QueryStepSyntax

    data class Walk(
        val meaning: RelationMeaning,
        val maximumDepth: TraversalDepthLimit,
        val strategy: TraversalStrategy,
    ) : QueryStepSyntax

    data class Concat(val input: QueryCompositionInput) : QueryStepSyntax

    data class Intersect(val right: QueryRetainedResult.Symbols) : QueryStepSyntax

    data class Union(val right: QueryRetainedResult.Symbols) : QueryStepSyntax

    data class Difference(val right: QueryRetainedResult.Symbols) : QueryStepSyntax

    data class Bind(val name: QueryBindingName) : QueryStepSyntax

    data class Join(val mode: QueryJoinMode, val right: QueryJoinInput) : QueryStepSyntax

    data object Distinct : QueryStepSyntax
}

/** A proven result can retain evidence; exact references must be revalidated during execution. */
sealed interface QueryCompositionInput {
    data class ExactReferences(val references: QueryExactReferences) : QueryCompositionInput

    data class Retained(val result: QueryRetainedResult.Symbols) : QueryCompositionInput
}

enum class QueryJoinModeFailure {
    DUPLICATE_OUTPUT_NAME
}

sealed interface QueryJoinMode {
    class Inner
    private constructor(
        val leftName: QueryBindingName,
        val rightName: QueryBindingName,
    ) : QueryJoinMode {
        companion object {
            fun create(
                leftName: QueryBindingName,
                rightName: QueryBindingName,
            ): Refinement<Inner, QueryJoinModeFailure> =
                if (leftName == rightName) {
                    Refinement.Rejected(QueryJoinModeFailure.DUPLICATE_OUTPUT_NAME)
                } else {
                    Refinement.Refined(Inner(leftName, rightName))
                }
        }

        override fun equals(other: Any?): Boolean =
            other is Inner && leftName == other.leftName && rightName == other.rightName

        override fun hashCode(): Int = 31 * leftName.hashCode() + rightName.hashCode()
    }

    data object Semi : QueryJoinMode

    data object Anti : QueryJoinMode
}

sealed interface QueryJoinInput {
    data class Named(val name: QueryBindingName) : QueryJoinInput

    data class Retained(val result: QueryRetainedResult.Symbols) : QueryJoinInput
}
