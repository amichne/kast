package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

enum class QuerySymbolField {
    NAME,
    LOCATION,
    SIGNATURE,
    SOURCE,
}

/** Optional presentation fields. Exact identity remains present through the returned ref. */
class QuerySymbolFields private constructor(val values: List<QuerySymbolField>) {
    companion object {
        fun from(raw: Set<QuerySymbolField>): Refinement<QuerySymbolFields, QueryCollectionFailure> =
            Refinement.Refined(QuerySymbolFields(raw.sortedBy { it.ordinal }))
    }

    override fun equals(other: Any?): Boolean = other is QuerySymbolFields && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

sealed interface QueryOutputSyntax {
    data class Symbols(val fields: QuerySymbolFields) : QueryOutputSyntax

    data object Occurrences : QueryOutputSyntax
}

data class QueryPlanSyntax(
    val source: QuerySourceSyntax,
    val steps: List<QueryStepSyntax>,
    val output: QueryOutputSyntax,
)

sealed interface QueryPlanAdmissionFailure {
    data class UnsupportedDeclarationKind(val kind: CompilerSymbolKind) : QueryPlanAdmissionFailure

    data object IncompleteRightInput : QueryPlanAdmissionFailure
}

enum class QuerySetOperator {
    INTERSECTION,
    UNION,
    DIFFERENCE,
}

sealed interface ExactQueryStage {
    data class Where(val predicate: QueryPredicate, val next: ExactQueryStage) : ExactQueryStage

    data class Related(val meaning: RelationMeaning, val next: ExactQueryStage) : ExactQueryStage

    data class Distinct(val next: ExactQueryStage) : ExactQueryStage

    data class Concat(val input: QueryCompositionInput, val next: ExactQueryStage) : ExactQueryStage

    data class Set
    internal constructor(
        val operator: QuerySetOperator,
        val right: QueryRetainedResult,
        val next: ExactQueryStage,
    ) : ExactQueryStage

    data class Emit(val output: QueryOutputSyntax) : ExactQueryStage
}

sealed interface AdmittedQueryPlan {
    data class Symbols
    internal constructor(
        val source: QueryDiscoverySyntax,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan

    data class ExactReferences
    internal constructor(
        val source: QueryExactReferences,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan

    data class Retained
    internal constructor(
        val source: QueryRetainedResult,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan
}

sealed interface QueryPlanAdmission {
    data class Admitted(val plan: AdmittedQueryPlan) : QueryPlanAdmission

    data class Rejected(val failure: QueryPlanAdmissionFailure) : QueryPlanAdmission
}

/** Pure plan compiler; every admitted stage consumes exact semantic symbols. */
object QueryPlanCompiler {
    fun admit(syntax: QueryPlanSyntax): QueryPlanAdmission {
        val source = syntax.source
        if (
            source is QuerySourceSyntax.Symbols &&
                CompilerSymbolKind.CONSTRUCTOR in source.discovery.declarationKinds.values
        ) {
            return QueryPlanAdmission.Rejected(
                QueryPlanAdmissionFailure.UnsupportedDeclarationKind(CompilerSymbolKind.CONSTRUCTOR)
            )
        }
        if (syntax.steps.filterIsInstance<QueryStepSyntax.Difference>().any { !it.right.provesAbsence() }) {
            return QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.IncompleteRightInput)
        }
        val stage = exactStage(syntax.steps, syntax.output)
        val plan =
            when (source) {
                is QuerySourceSyntax.Symbols -> AdmittedQueryPlan.Symbols(source.discovery, stage)
                is QuerySourceSyntax.ExactReferences -> AdmittedQueryPlan.ExactReferences(source.references, stage)
                is QuerySourceSyntax.Retained -> AdmittedQueryPlan.Retained(source.result, stage)
            }
        return QueryPlanAdmission.Admitted(plan)
    }

    private fun exactStage(steps: List<QueryStepSyntax>, output: QueryOutputSyntax): ExactQueryStage {
        var stage: ExactQueryStage = ExactQueryStage.Emit(output)
        for (step in steps.asReversed()) {
            stage =
                when (step) {
                    QueryStepSyntax.Distinct -> ExactQueryStage.Distinct(stage)
                    is QueryStepSyntax.Where -> ExactQueryStage.Where(step.predicate, stage)
                    is QueryStepSyntax.Related -> ExactQueryStage.Related(step.meaning, stage)
                    is QueryStepSyntax.Concat -> ExactQueryStage.Concat(step.input, stage)
                    is QueryStepSyntax.Intersect ->
                        ExactQueryStage.Set(QuerySetOperator.INTERSECTION, step.right, stage)
                    is QueryStepSyntax.Union -> ExactQueryStage.Set(QuerySetOperator.UNION, step.right, stage)
                    is QueryStepSyntax.Difference -> ExactQueryStage.Set(QuerySetOperator.DIFFERENCE, step.right, stage)
                }
        }
        return stage
    }
}

private fun QueryRetainedResult.provesAbsence(): Boolean =
    coverage is QueryCoverage.Complete && failures.isEmpty() && omissions.isEmpty() && producerProgress == null

internal fun AdmittedQueryPlan.composedInputLeases(): List<SemanticReadAuthority> =
    when (this) {
        is AdmittedQueryPlan.Symbols -> stage.composedInputLeases()
        is AdmittedQueryPlan.ExactReferences -> stage.composedInputLeases()
        is AdmittedQueryPlan.Retained -> stage.composedInputLeases()
    }

private fun ExactQueryStage.composedInputLeases(): List<SemanticReadAuthority> =
    when (this) {
        is ExactQueryStage.Concat ->
            (when (val source = input) {
                is QueryCompositionInput.ExactReferences -> source.references.values.map { it.lease }
                is QueryCompositionInput.Retained -> listOf(source.result.lease)
            }) + next.composedInputLeases()
        is ExactQueryStage.Set -> listOf(right.lease) + next.composedInputLeases()
        is ExactQueryStage.Distinct -> next.composedInputLeases()
        is ExactQueryStage.Where -> next.composedInputLeases()
        is ExactQueryStage.Related -> next.composedInputLeases()
        is ExactQueryStage.Emit -> emptyList()
    }
