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

    @Test
    fun `named join resolves only an earlier unique sealed binding`() {
        val earlier = bindingName("earlier")
        val syntax =
            QueryPlanSyntax(
                source = QuerySourceSyntax.Symbols(discovery()),
                steps =
                    listOf(
                        QueryStepSyntax.Bind(earlier),
                        QueryStepSyntax.Join(QueryJoinMode.Semi, QueryJoinInput.Named(earlier)),
                    ),
                output = QueryOutputSyntax.Symbols(symbolFields()),
            )

        val admitted = assertInstanceOf(QueryPlanAdmission.Admitted::class.java, QueryPlanCompiler.admit(syntax))
        val source = assertInstanceOf(AdmittedQueryPlan.Symbols::class.java, admitted.plan)
        val bind = assertInstanceOf(ExactQueryStage.Bind::class.java, source.stage)
        assertEquals(earlier, bind.name)
        assertInstanceOf(ExactQueryStage.Join::class.java, bind.next)

        assertEquals(
            QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.UnknownBindingName(earlier)),
            QueryPlanCompiler.admit(syntax.copy(steps = syntax.steps.reversed())),
        )
        assertEquals(
            QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.DuplicateBindingName(earlier)),
            QueryPlanCompiler.admit(
                syntax.copy(steps = listOf(QueryStepSyntax.Bind(earlier), QueryStepSyntax.Bind(earlier)))
            ),
        )
    }

    @Test
    fun `inner join requires typed projection before symbol stages`() {
        val left = bindingName("left")
        val right = bindingName("right")
        val inner = QueryJoinMode.Inner.create(left, right).refined()
        val join = QueryStepSyntax.Join(inner, QueryJoinInput.Named(left))
        val syntax =
            QueryPlanSyntax(
                source = QuerySourceSyntax.Symbols(discovery()),
                steps = listOf(QueryStepSyntax.Bind(left), join),
                output = QueryOutputSyntax.BindingRows,
            )

        assertInstanceOf(QueryPlanAdmission.Admitted::class.java, QueryPlanCompiler.admit(syntax))
        assertEquals(
            QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.OutputTypeMismatch),
            QueryPlanCompiler.admit(syntax.copy(steps = syntax.steps + QueryStepSyntax.Distinct)),
        )
        assertInstanceOf(
            QueryPlanAdmission.Admitted::class.java,
            QueryPlanCompiler.admit(
                syntax.copy(
                    steps = syntax.steps + QueryStepSyntax.ProjectBinding(left) + QueryStepSyntax.Distinct,
                    output = QueryOutputSyntax.Symbols(symbolFields()),
                )
            ),
        )
        assertEquals(
            QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.UnknownBindingName(bindingName("missing"))),
            QueryPlanCompiler.admit(
                syntax.copy(
                    steps = syntax.steps + QueryStepSyntax.ProjectBinding(bindingName("missing")),
                    output = QueryOutputSyntax.Symbols(symbolFields()),
                )
            ),
        )
        assertEquals(
            QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.OutputTypeMismatch),
            QueryPlanCompiler.admit(syntax.copy(output = QueryOutputSyntax.Symbols(symbolFields()))),
        )
        assertEquals(
            Refinement.Rejected(QueryJoinModeFailure.DUPLICATE_OUTPUT_NAME),
            QueryJoinMode.Inner.create(left, left),
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

    private fun bindingName(value: String): QueryBindingName = QueryBindingName.parse(value).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refined value, got $failure")
        }
}
