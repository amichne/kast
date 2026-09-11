package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind

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
}

sealed interface QueryStepSyntax {
    data object Inspect : QueryStepSyntax

    data class Where(val predicate: QueryPredicate) : QueryStepSyntax

    data class Related(val meaning: RelationMeaning) : QueryStepSyntax

    data object Distinct : QueryStepSyntax
}

enum class QueryCandidateField {
    NAME,
    LOCATION,
}

/** Optional presentation fields. The proof-carrying ref is always emitted independently. */
class QueryCandidateFields private constructor(val values: List<QueryCandidateField>) {
    companion object {
        fun from(raw: Set<QueryCandidateField>): Refinement<QueryCandidateFields, QueryCollectionFailure> =
            Refinement.Refined(QueryCandidateFields(raw.sortedBy { it.ordinal }))
    }

    override fun equals(other: Any?): Boolean = other is QueryCandidateFields && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

enum class QuerySymbolField {
    NAME,
    LOCATION,
    SIGNATURE,
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
    data class Candidates(val fields: QueryCandidateFields) : QueryOutputSyntax

    data class Symbols(val fields: QuerySymbolFields) : QueryOutputSyntax
}

data class QueryPlanSyntax(
    val source: QuerySourceSyntax,
    val steps: List<QueryStepSyntax>,
    val output: QueryOutputSyntax,
)

enum class QueryElementType {
    DECLARATION_CANDIDATE,
    EXACT_SYMBOL,
}

enum class QueryAdmissionCorrection {
    INSERT_INSPECT,
    REMOVE_INSPECT,
    SELECT_SYMBOL_OUTPUT,
}

enum class QueryStagePositionFailure {
    NEGATIVE
}

@JvmInline
value class QueryStagePosition private constructor(val value: Int) {
    companion object {
        fun parse(raw: Int): Refinement<QueryStagePosition, QueryStagePositionFailure> =
            if (raw < 0) {
                Refinement.Rejected(QueryStagePositionFailure.NEGATIVE)
            } else {
                Refinement.Refined(QueryStagePosition(raw))
            }
    }
}

sealed interface QueryPlanAdmissionFailure {
    data class UnsupportedDeclarationKind(val kind: CompilerSymbolKind) : QueryPlanAdmissionFailure

    data class StageTypeMismatch(
        val position: QueryStagePosition,
        val required: QueryElementType,
        val actual: QueryElementType,
        val correction: QueryAdmissionCorrection,
    ) : QueryPlanAdmissionFailure

    data class OutputTypeMismatch(
        val position: QueryStagePosition,
        val required: QueryElementType,
        val actual: QueryElementType,
        val correction: QueryAdmissionCorrection,
    ) : QueryPlanAdmissionFailure
}

sealed interface CandidateQueryStage {
    data class Distinct(val next: CandidateQueryStage) : CandidateQueryStage

    data class Inspect(val next: ExactQueryStage) : CandidateQueryStage

    data class Emit(val fields: QueryCandidateFields) : CandidateQueryStage
}

sealed interface ExactQueryStage {
    data class Where(val predicate: QueryPredicate, val next: ExactQueryStage) : ExactQueryStage

    data class Related(val meaning: RelationMeaning, val next: ExactQueryStage) : ExactQueryStage

    data class Distinct(val next: ExactQueryStage) : ExactQueryStage

    data class Emit(val fields: QuerySymbolFields) : ExactQueryStage
}

sealed interface AdmittedQueryPlan {
    data class Candidates
    internal constructor(
        val source: QueryDiscoverySyntax,
        val stage: CandidateQueryStage,
    ) : AdmittedQueryPlan

    data class Symbols
    internal constructor(
        val source: QueryDiscoverySyntax,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan

    data class CandidateReferences
    internal constructor(
        val source: QueryCandidateReferences,
        val stage: CandidateQueryStage,
    ) : AdmittedQueryPlan

    data class ExactReferences
    internal constructor(
        val source: QueryExactReferences,
        val stage: ExactQueryStage,
    ) : AdmittedQueryPlan
}

sealed interface QueryPlanAdmission {
    data class Admitted(val plan: AdmittedQueryPlan) : QueryPlanAdmission

    data class Rejected(val failure: QueryPlanAdmissionFailure) : QueryPlanAdmission
}

/** Pure plan compiler; successful values contain only type-compatible stage transitions. */
object QueryPlanCompiler {
    fun admit(syntax: QueryPlanSyntax): QueryPlanAdmission {
        val discovery =
            when (val source = syntax.source) {
                is QuerySourceSyntax.Candidates -> source.discovery
                is QuerySourceSyntax.Symbols -> source.discovery
                is QuerySourceSyntax.CandidateReferences,
                is QuerySourceSyntax.ExactReferences -> null
            }
        if (discovery != null && CompilerSymbolKind.CONSTRUCTOR in discovery.declarationKinds.values) {
            return QueryPlanAdmission.Rejected(
                QueryPlanAdmissionFailure.UnsupportedDeclarationKind(CompilerSymbolKind.CONSTRUCTOR)
            )
        }
        return when (val source = syntax.source) {
            is QuerySourceSyntax.Candidates ->
                when (
                    val stage =
                        candidateStage(
                            syntax.steps,
                            0,
                            syntax.output,
                        )
                ) {
                    is CandidateStageAdmission.Admitted ->
                        QueryPlanAdmission.Admitted(AdmittedQueryPlan.Candidates(source.discovery, stage.stage))
                    is CandidateStageAdmission.Rejected -> QueryPlanAdmission.Rejected(stage.failure)
                }
            is QuerySourceSyntax.Symbols ->
                exactPlan(
                    source.discovery,
                    syntax.steps,
                    syntax.output,
                )
            is QuerySourceSyntax.CandidateReferences ->
                when (val stage = candidateStage(syntax.steps, 0, syntax.output)) {
                    is CandidateStageAdmission.Admitted ->
                        QueryPlanAdmission.Admitted(
                            AdmittedQueryPlan.CandidateReferences(source.references, stage.stage)
                        )
                    is CandidateStageAdmission.Rejected -> QueryPlanAdmission.Rejected(stage.failure)
                }
            is QuerySourceSyntax.ExactReferences ->
                when (val stage = exactStage(syntax.steps, 0, syntax.output)) {
                    is ExactStageAdmission.Admitted ->
                        QueryPlanAdmission.Admitted(AdmittedQueryPlan.ExactReferences(source.references, stage.stage))
                    is ExactStageAdmission.Rejected -> QueryPlanAdmission.Rejected(stage.failure)
                }
        }
    }

    private fun exactPlan(
        source: QueryDiscoverySyntax,
        steps: List<QueryStepSyntax>,
        output: QueryOutputSyntax,
    ): QueryPlanAdmission =
        when (val stage = exactStage(steps, 0, output)) {
            is ExactStageAdmission.Admitted ->
                QueryPlanAdmission.Admitted(AdmittedQueryPlan.Symbols(source, stage.stage))
            is ExactStageAdmission.Rejected -> QueryPlanAdmission.Rejected(stage.failure)
        }

    private fun candidateStage(
        steps: List<QueryStepSyntax>,
        index: Int,
        output: QueryOutputSyntax,
    ): CandidateStageAdmission {
        val step =
            steps.getOrNull(index)
                ?: return when (output) {
                    is QueryOutputSyntax.Candidates ->
                        CandidateStageAdmission.Admitted(CandidateQueryStage.Emit(output.fields))
                    is QueryOutputSyntax.Symbols ->
                        CandidateStageAdmission.Rejected(
                            QueryPlanAdmissionFailure.OutputTypeMismatch(
                                position(index),
                                QueryElementType.EXACT_SYMBOL,
                                QueryElementType.DECLARATION_CANDIDATE,
                                QueryAdmissionCorrection.INSERT_INSPECT,
                            )
                        )
                }
        return when (step) {
            QueryStepSyntax.Distinct ->
                candidateStage(
                        steps,
                        index + 1,
                        output,
                    )
                    .map { CandidateQueryStage.Distinct(it) }
            QueryStepSyntax.Inspect ->
                when (val next = exactStage(steps, index + 1, output)) {
                    is ExactStageAdmission.Admitted ->
                        CandidateStageAdmission.Admitted(CandidateQueryStage.Inspect(next.stage))
                    is ExactStageAdmission.Rejected -> CandidateStageAdmission.Rejected(next.failure)
                }
            is QueryStepSyntax.Related,
            is QueryStepSyntax.Where ->
                CandidateStageAdmission.Rejected(
                    stageMismatch(
                        index,
                        QueryElementType.EXACT_SYMBOL,
                        QueryElementType.DECLARATION_CANDIDATE,
                        QueryAdmissionCorrection.INSERT_INSPECT,
                    )
                )
        }
    }

    private fun exactStage(
        steps: List<QueryStepSyntax>,
        index: Int,
        output: QueryOutputSyntax,
    ): ExactStageAdmission {
        val step =
            steps.getOrNull(index)
                ?: return when (output) {
                    is QueryOutputSyntax.Symbols -> ExactStageAdmission.Admitted(ExactQueryStage.Emit(output.fields))
                    is QueryOutputSyntax.Candidates ->
                        ExactStageAdmission.Rejected(
                            outputMismatch(index, QueryElementType.DECLARATION_CANDIDATE, QueryElementType.EXACT_SYMBOL)
                        )
                }
        return when (step) {
            QueryStepSyntax.Inspect ->
                ExactStageAdmission.Rejected(
                    stageMismatch(
                        index,
                        QueryElementType.DECLARATION_CANDIDATE,
                        QueryElementType.EXACT_SYMBOL,
                        QueryAdmissionCorrection.REMOVE_INSPECT,
                    )
                )
            QueryStepSyntax.Distinct -> exactStage(steps, index + 1, output).map { ExactQueryStage.Distinct(it) }
            is QueryStepSyntax.Where ->
                exactStage(steps, index + 1, output).map { ExactQueryStage.Where(step.predicate, it) }
            is QueryStepSyntax.Related ->
                exactStage(steps, index + 1, output).map { ExactQueryStage.Related(step.meaning, it) }
        }
    }

    private fun stageMismatch(
        index: Int,
        required: QueryElementType,
        actual: QueryElementType,
        correction: QueryAdmissionCorrection,
    ): QueryPlanAdmissionFailure.StageTypeMismatch =
        QueryPlanAdmissionFailure.StageTypeMismatch(
            position(index),
            required,
            actual,
            correction,
        )

    private fun outputMismatch(
        index: Int,
        required: QueryElementType,
        actual: QueryElementType,
    ): QueryPlanAdmissionFailure.OutputTypeMismatch =
        QueryPlanAdmissionFailure.OutputTypeMismatch(
            position(index),
            required,
            actual,
            if (required == QueryElementType.EXACT_SYMBOL) QueryAdmissionCorrection.INSERT_INSPECT
            else QueryAdmissionCorrection.SELECT_SYMBOL_OUTPUT,
        )

    private fun position(raw: Int): QueryStagePosition =
        when (val parsed = QueryStagePosition.parse(raw)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error("A plan index cannot be negative")
        }
}

private sealed interface CandidateStageAdmission {
    data class Admitted(val stage: CandidateQueryStage) : CandidateStageAdmission

    data class Rejected(val failure: QueryPlanAdmissionFailure) : CandidateStageAdmission
}

private sealed interface ExactStageAdmission {
    data class Admitted(val stage: ExactQueryStage) : ExactStageAdmission

    data class Rejected(val failure: QueryPlanAdmissionFailure) : ExactStageAdmission
}

private fun CandidateStageAdmission.map(
    transform: (CandidateQueryStage) -> CandidateQueryStage
): CandidateStageAdmission =
    when (this) {
        is CandidateStageAdmission.Admitted -> CandidateStageAdmission.Admitted(transform(stage))
        is CandidateStageAdmission.Rejected -> this
    }

private fun ExactStageAdmission.map(transform: (ExactQueryStage) -> ExactQueryStage): ExactStageAdmission =
    when (this) {
        is ExactStageAdmission.Admitted -> ExactStageAdmission.Admitted(transform(stage))
        is ExactStageAdmission.Rejected -> this
    }
