package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryPlanCompilerTest {
    @Test
    fun `candidate relation is rejected with an inspect correction`() {
        val syntax = QueryPlanSyntax(
            source = QuerySourceSyntax.Candidates(discovery()),
            steps = listOf(QueryStepSyntax.Related(RelationMeaning.Callers)),
            output = QueryOutputSyntax.Symbols(symbolFields()),
        )

        assertEquals(
            QueryPlanAdmission.Rejected(
                QueryPlanAdmissionFailure.StageTypeMismatch(
                    position = QueryStagePosition.parse(0).refined(),
                    required = QueryElementType.EXACT_SYMBOL,
                    actual = QueryElementType.DECLARATION_CANDIDATE,
                    correction = QueryAdmissionCorrection.INSERT_INSPECT,
                ),
            ),
            QueryPlanCompiler.admit(syntax),
        )
    }

    @Test
    fun `inspection ratchets candidates into an exact stage chain`() {
        val syntax = QueryPlanSyntax(
            source = QuerySourceSyntax.Candidates(discovery()),
            steps = listOf(
                QueryStepSyntax.Distinct,
                QueryStepSyntax.Inspect,
                QueryStepSyntax.Where(
                    QueryPredicate.Visibility(visibility(DeclarationVisibility.PUBLIC)),
                ),
                QueryStepSyntax.Related(RelationMeaning.Inheritors),
                QueryStepSyntax.Distinct,
            ),
            output = QueryOutputSyntax.Symbols(symbolFields()),
        )

        val admitted = QueryPlanCompiler.admit(syntax)

        val plan = assertInstanceOf(QueryPlanAdmission.Admitted::class.java, admitted).plan
        val candidatePlan = assertInstanceOf(AdmittedQueryPlan.Candidates::class.java, plan)
        val candidateDistinct = assertInstanceOf(
            CandidateQueryStage.Distinct::class.java,
            candidatePlan.stage,
        )
        val inspect = assertInstanceOf(
            CandidateQueryStage.Inspect::class.java,
            candidateDistinct.next,
        )
        val where = assertInstanceOf(ExactQueryStage.Where::class.java, inspect.next)
        val related = assertInstanceOf(ExactQueryStage.Related::class.java, where.next)
        assertEquals(RelationMeaning.Inheritors, related.meaning)
        assertInstanceOf(ExactQueryStage.Distinct::class.java, related.next)
    }

    @Test
    fun `symbols source begins with exact evidence`() {
        val syntax = QueryPlanSyntax(
            source = QuerySourceSyntax.Symbols(discovery()),
            steps = listOf(QueryStepSyntax.Related(RelationMeaning.Callees)),
            output = QueryOutputSyntax.Symbols(symbolFields()),
        )

        val plan = assertInstanceOf(
            QueryPlanAdmission.Admitted::class.java,
            QueryPlanCompiler.admit(syntax),
        ).plan

        val symbols = assertInstanceOf(AdmittedQueryPlan.Symbols::class.java, plan)
        assertInstanceOf(ExactQueryStage.Related::class.java, symbols.stage)
    }

    @Test
    fun `candidate source requires inspect before symbol output`() {
        val syntax = QueryPlanSyntax(
            source = QuerySourceSyntax.Candidates(discovery()),
            steps = emptyList(),
            output = QueryOutputSyntax.Symbols(symbolFields()),
        )

        assertEquals(
            QueryPlanAdmission.Rejected(
                QueryPlanAdmissionFailure.OutputTypeMismatch(
                    position = QueryStagePosition.parse(0).refined(),
                    required = QueryElementType.EXACT_SYMBOL,
                    actual = QueryElementType.DECLARATION_CANDIDATE,
                    correction = QueryAdmissionCorrection.INSERT_INSPECT,
                ),
            ),
            QueryPlanCompiler.admit(syntax),
        )
    }

    @Test
    fun `exact source cannot be projected as candidates`() {
        val syntax = QueryPlanSyntax(
            source = QuerySourceSyntax.Symbols(discovery()),
            steps = emptyList(),
            output = QueryOutputSyntax.Candidates(candidateFields()),
        )

        assertEquals(
            QueryPlanAdmission.Rejected(
                QueryPlanAdmissionFailure.OutputTypeMismatch(
                    position = QueryStagePosition.parse(0).refined(),
                    required = QueryElementType.DECLARATION_CANDIDATE,
                    actual = QueryElementType.EXACT_SYMBOL,
                    correction = QueryAdmissionCorrection.SELECT_SYMBOL_OUTPUT,
                ),
            ),
            QueryPlanCompiler.admit(syntax),
        )
    }

    @Test
    fun `unsupported exhaustive declaration kind is rejected before execution`() {
        val syntax = QueryPlanSyntax(
            source = QuerySourceSyntax.Symbols(discovery(CompilerSymbolKind.CONSTRUCTOR)),
            steps = emptyList(),
            output = QueryOutputSyntax.Symbols(symbolFields()),
        )

        assertEquals(
            QueryPlanAdmission.Rejected(
                QueryPlanAdmissionFailure.UnsupportedDeclarationKind(
                    CompilerSymbolKind.CONSTRUCTOR,
                ),
            ),
            QueryPlanCompiler.admit(syntax),
        )
    }

    private fun discovery(
        kind: CompilerSymbolKind = CompilerSymbolKind.CLASSLIKE,
    ): QueryDiscoverySyntax = QueryDiscoverySyntax(
        match = QueryMatch.Name(
            SymbolDiscoveryPattern.parse("PaymentService").refined(),
            SymbolDiscoveryMatch.EXACT_NAME,
        ),
        scope = QueryScope.Unrestricted,
        declarationKinds = QueryDeclarationKinds.from(setOf(kind)).refined(),
    )

    private fun visibility(value: DeclarationVisibility): QueryVisibilitySelection =
        QueryVisibilitySelection.from(setOf(value)).refined()

    private fun symbolFields(): QuerySymbolFields = QuerySymbolFields.from(
        setOf(QuerySymbolField.NAME, QuerySymbolField.LOCATION, QuerySymbolField.SIGNATURE),
    ).refined()

    private fun candidateFields(): QueryCandidateFields = QueryCandidateFields.from(
        setOf(QueryCandidateField.NAME, QueryCandidateField.LOCATION),
    ).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
