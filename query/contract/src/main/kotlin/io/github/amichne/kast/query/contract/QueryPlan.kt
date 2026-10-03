package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalStrategy
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
    data class ImpactWitness(val section: QueryImpactWitnessSection) : QueryOutputSyntax

    data class Symbols(val fields: QuerySymbolFields) : QueryOutputSyntax

    data object Occurrences : QueryOutputSyntax

    data object TraversalRecords : QueryOutputSyntax

    data object BindingRows : QueryOutputSyntax

    data object ValuePaths : QueryOutputSyntax
}

data class QueryPlanSyntax(
    val source: QuerySourceSyntax,
    val steps: List<QueryStepSyntax>,
    val output: QueryOutputSyntax,
)

sealed interface QueryPlanAdmissionFailure {
    data class UnsupportedDeclarationKind(val kind: CompilerSymbolKind) : QueryPlanAdmissionFailure

    data object IncompleteRightInput : QueryPlanAdmissionFailure

    data class UnknownBindingName(val name: QueryBindingName) : QueryPlanAdmissionFailure

    data object OutputTypeMismatch : QueryPlanAdmissionFailure
}

enum class QuerySetOperator {
    INTERSECTION,
    DIFFERENCE,
}

sealed interface ExactQueryStage {
    data class ProjectBinding(val name: QueryBindingName, val next: ExactQueryStage) : ExactQueryStage

    data class Where(val predicate: QueryPredicate, val next: ExactQueryStage) : ExactQueryStage

    data class Related(
        val meaning: RelationMeaning,
        val next: ExactQueryStage,
        val expansion: RelationSearchBoundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
    ) : ExactQueryStage

    data class Walk(
        val meaning: RelationMeaning,
        val maximumDepth: TraversalDepthLimit,
        val strategy: TraversalStrategy,
        val next: ExactQueryStage,
        val expansion: RelationSearchBoundary = RelationSearchBoundary.RETAINED_SUBJECT,
    ) : ExactQueryStage

    data class Distinct(val next: ExactQueryStage) : ExactQueryStage

    data class Concat(val input: QueryCompositionInput, val next: ExactQueryStage) : ExactQueryStage

    data class Set
    internal constructor(
        val operator: QuerySetOperator,
        val right: QueryRetainedResult.Symbols,
        val next: ExactQueryStage,
    ) : ExactQueryStage

    data class Join(
        val mode: QueryJoinMode,
        val right: QueryRetainedResult.Symbols,
        val next: ExactQueryStage,
    ) : ExactQueryStage

    data class Emit(val output: QueryOutputSyntax) : ExactQueryStage
}

sealed interface AdmittedQueryPlan {
    data class Impact internal constructor(val source: QueryImpactSource, val stage: ExactQueryStage) :
        AdmittedQueryPlan

    data class Symbols
    internal constructor(
        val source: QueryDiscoverySyntax,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan

    data class Location
    internal constructor(
        val source: QueryContainingDeclaration,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan

    data class Text
    internal constructor(
        val source: QueryTextDiscoverySyntax,
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
        val declarationKinds =
            when (source) {
                is QuerySourceSyntax.Symbols -> source.discovery.declarationKinds.values
                is QuerySourceSyntax.Text -> source.discovery.declarationKinds.values
                is QuerySourceSyntax.Impact,
                is QuerySourceSyntax.Location,
                is QuerySourceSyntax.ExactReferences,
                is QuerySourceSyntax.Retained -> emptyList()
            }
        if (CompilerSymbolKind.CONSTRUCTOR in declarationKinds) {
            return QueryPlanAdmission.Rejected(
                QueryPlanAdmissionFailure.UnsupportedDeclarationKind(CompilerSymbolKind.CONSTRUCTOR)
            )
        }
        when (val rows = admitQueryRows(source, syntax.steps, syntax.output)) {
            is Refinement.Rejected -> return QueryPlanAdmission.Rejected(rows.failure)
            is Refinement.Refined -> Unit
        }
        val stage = exactStage(syntax.steps, syntax.output)
        val plan =
            when (source) {
                is QuerySourceSyntax.Impact -> AdmittedQueryPlan.Impact(source.source, stage)
                is QuerySourceSyntax.Symbols -> AdmittedQueryPlan.Symbols(source.discovery, stage)
                is QuerySourceSyntax.Text -> AdmittedQueryPlan.Text(source.discovery, stage)
                is QuerySourceSyntax.Location -> AdmittedQueryPlan.Location(source.target, stage)
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
                    is QueryStepSyntax.ProjectBinding -> ExactQueryStage.ProjectBinding(step.name, stage)
                    QueryStepSyntax.Distinct -> ExactQueryStage.Distinct(stage)
                    is QueryStepSyntax.Where -> ExactQueryStage.Where(step.predicate, stage)
                    is QueryStepSyntax.Related -> ExactQueryStage.Related(step.meaning, stage, step.expansion)
                    is QueryStepSyntax.Walk ->
                        ExactQueryStage.Walk(step.meaning, step.maximumDepth, step.strategy, stage, step.expansion)
                    is QueryStepSyntax.Concat -> ExactQueryStage.Concat(step.input, stage)
                    is QueryStepSyntax.Intersect ->
                        ExactQueryStage.Set(QuerySetOperator.INTERSECTION, step.right, stage)
                    is QueryStepSyntax.Union ->
                        ExactQueryStage.Concat(
                            QueryCompositionInput.Retained(step.right),
                            ExactQueryStage.Distinct(stage),
                        )
                    is QueryStepSyntax.Difference -> ExactQueryStage.Set(QuerySetOperator.DIFFERENCE, step.right, stage)
                    is QueryStepSyntax.Join -> ExactQueryStage.Join(step.mode, step.right, stage)
                }
        }
        return stage
    }
}

internal fun AdmittedQueryPlan.composedInputLeases(): List<SemanticReadAuthority> =
    when (this) {
        is AdmittedQueryPlan.Impact -> stage.composedInputLeases()
        is AdmittedQueryPlan.Symbols -> stage.composedInputLeases()
        is AdmittedQueryPlan.Text -> stage.composedInputLeases()
        is AdmittedQueryPlan.Location -> stage.composedInputLeases()
        is AdmittedQueryPlan.ExactReferences -> stage.composedInputLeases()
        is AdmittedQueryPlan.Retained -> stage.composedInputLeases()
    }

private fun ExactQueryStage.composedInputLeases(): List<SemanticReadAuthority> =
    when (this) {
        is ExactQueryStage.ProjectBinding -> next.composedInputLeases()
        is ExactQueryStage.Concat ->
            (when (val source = input) {
                is QueryCompositionInput.ExactReferences -> source.references.values.map { it.lease }
                is QueryCompositionInput.Retained -> listOf(source.result.lease)
            }) + next.composedInputLeases()
        is ExactQueryStage.Set -> listOf(right.lease) + next.composedInputLeases()
        is ExactQueryStage.Join -> listOf(right.lease) + next.composedInputLeases()
        is ExactQueryStage.Distinct -> next.composedInputLeases()
        is ExactQueryStage.Where -> next.composedInputLeases()
        is ExactQueryStage.Related -> next.composedInputLeases()
        is ExactQueryStage.Walk -> next.composedInputLeases()
        is ExactQueryStage.Emit -> emptyList()
    }
