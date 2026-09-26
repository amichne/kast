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
    fun `discovery source compiles an ordered exact-symbol stage chain`() {
        val syntax =
            QueryPlanSyntax(
                source = QuerySourceSyntax.Symbols(discovery()),
                steps =
                    listOf(
                        QueryStepSyntax.Where(QueryPredicate.Visibility(visibility(DeclarationVisibility.PUBLIC))),
                        QueryStepSyntax.Related(RelationMeaning.Inheritors),
                        QueryStepSyntax.Distinct,
                    ),
                output = QueryOutputSyntax.Symbols(symbolFields()),
            )

        val plan =
            assertInstanceOf(
                    QueryPlanAdmission.Admitted::class.java,
                    QueryPlanCompiler.admit(syntax),
                )
                .plan
        val symbols = assertInstanceOf(AdmittedQueryPlan.Symbols::class.java, plan)
        val where = assertInstanceOf(ExactQueryStage.Where::class.java, symbols.stage)
        val related = assertInstanceOf(ExactQueryStage.Related::class.java, where.next)
        assertEquals(RelationMeaning.Inheritors, related.meaning)
        assertInstanceOf(ExactQueryStage.Distinct::class.java, related.next)
    }

    @Test
    fun `ref only output remains a valid exact projection`() {
        val syntax =
            QueryPlanSyntax(
                source = QuerySourceSyntax.Symbols(discovery()),
                steps = emptyList(),
                output = QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
            )

        val plan =
            assertInstanceOf(
                    QueryPlanAdmission.Admitted::class.java,
                    QueryPlanCompiler.admit(syntax),
                )
                .plan
        val stage = assertInstanceOf(ExactQueryStage.Emit::class.java, (plan as AdmittedQueryPlan.Symbols).stage)
        val output = assertInstanceOf(QueryOutputSyntax.Symbols::class.java, stage.output)
        assertEquals(emptyList<QuerySymbolField>(), output.fields.values)
    }

    @Test
    fun `unsupported exhaustive declaration kind is rejected before execution`() {
        val syntax =
            QueryPlanSyntax(
                source = QuerySourceSyntax.Symbols(discovery(CompilerSymbolKind.CONSTRUCTOR)),
                steps = emptyList(),
                output = QueryOutputSyntax.Symbols(symbolFields()),
            )

        assertEquals(
            QueryPlanAdmission.Rejected(
                QueryPlanAdmissionFailure.UnsupportedDeclarationKind(CompilerSymbolKind.CONSTRUCTOR)
            ),
            QueryPlanCompiler.admit(syntax),
        )
    }

    private fun discovery(kind: CompilerSymbolKind = CompilerSymbolKind.CLASSLIKE): QueryDiscoverySyntax =
        QueryDiscoverySyntax(
            match =
                QueryMatch.Name(
                    SymbolDiscoveryPattern.parse("PaymentService").refined(),
                    SymbolDiscoveryMatch.EXACT_NAME,
                ),
            scope = QueryScope.Unrestricted,
            declarationKinds = QueryDeclarationKinds.from(setOf(kind)).refined(),
        )

    private fun visibility(value: DeclarationVisibility): QueryVisibilitySelection =
        QueryVisibilitySelection.from(setOf(value)).refined()

    private fun symbolFields(): QuerySymbolFields =
        QuerySymbolFields.from(setOf(QuerySymbolField.NAME, QuerySymbolField.LOCATION, QuerySymbolField.SIGNATURE))
            .refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refined value, got $failure")
        }
}
