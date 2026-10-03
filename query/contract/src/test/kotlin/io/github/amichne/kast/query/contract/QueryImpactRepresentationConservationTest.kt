package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ContractModelIdentity
import io.github.amichne.kast.relation.contract.ExactModelCallablePosition
import io.github.amichne.kast.relation.contract.ModelCallableReference
import io.github.amichne.kast.relation.contract.ModelIdentifier
import io.github.amichne.kast.relation.contract.ModelRuleReference
import io.github.amichne.kast.relation.contract.ModelValuePosition
import io.github.amichne.kast.relation.contract.ModelVersion
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RepresentationDomain
import io.github.amichne.kast.relation.contract.RepresentationEvidence
import io.github.amichne.kast.relation.contract.RepresentationModelApplication
import io.github.amichne.kast.relation.contract.RepresentationRule
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class QueryImpactRepresentationConservationTest {
    @Test
    fun `every applicable consumer expectation requires its own retained route`() {
        val f = Fixture()
        val rules =
            listOf("first", "second").map {
                RepresentationRule.ConsumerExpectation.admit(f.rule(it), f.input, f.state).value()
            }
        val paths = rules.map { f.path(QueryImpactTerminal.Consumer(f.arrival.expect(it).value())) }
        assertEquals(Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH), f.ledger(rules, paths.take(1)))
        assertEquals(QueryImpactClosure.Discharged, f.ledger(rules, paths).value().closure)
    }

    @Test
    fun `every applicable transfer model requires its own retained route`() {
        assertModeledConservation { f, name ->
            RepresentationRule.Transfer.admit(f.rule(name), f.input, f.output).value()
        }
    }

    @Test
    fun `every applicable transformation model requires its own retained route`() {
        assertModeledConservation { f, name ->
            RepresentationRule.Transformation.admit(f.rule(name), f.input, f.output, f.state, f.state).value()
        }
    }

    @Test
    fun `transformation input mismatch stays an explicit unknown state`() {
        val f = Fixture()
        val other = f.state.domain.state(id("B")).value()
        val model =
            RepresentationRule.Transformation.admit(f.rule("mismatch"), f.input, f.output, other, f.state).value()
        val ledger = f.ledger(listOf(model), listOf(f.modeledPath(model)), f.resultObservation).value()
        assertEquals(
            setOf(QueryImpactRequiredObligation.REPRESENTATION_STATE),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
    }

    private fun assertModeledConservation(model: (Fixture, String) -> RepresentationRule) {
        val f = Fixture()
        val rules = listOf("first", "second").map { model(f, it) }
        val paths = rules.map { f.modeledPath(it) }
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
            f.ledger(rules, paths.take(1), f.resultObservation),
        )
        assertEquals(QueryImpactClosure.Discharged, f.ledger(rules, paths, f.resultObservation).value().closure)
    }

    private class Fixture {
        val base = QueryImpactLedgerTest.Fixture()
        private val identity =
            ContractModelIdentity(id("representation"), ModelVersion.parse(1).value(), id("review:913"))
        val state = RepresentationDomain.admit(identity, listOf(id("A"), id("B"))).value().state(id("A")).value()
        private val declaration =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    base.file,
                    210,
                    220,
                    "sink",
                    "fixture.sink",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("fixture.sink", null, emptyList(), listOf("kotlin.String"), 0)
                        .value(),
                )
                .value()
        private val callable = RelationEndpoint.resolve(base.lease, base.scope, declaration).value()
        val call =
            ValueInvocation.fromCompiler(base.owner, ExactDeclarationTextRange.parse(20, 30).value(), callable).value()
        private val argument = ValueArgumentPosition.parse(0).value()
        val input = bind(callable, ModelValuePosition.Argument(argument))
        val output = bind(callable, ModelValuePosition.Result)
        private val site =
            ValueSite.fromCompiler(
                    base.owner,
                    ExactDeclarationTextRange.parse(21, 22).value(),
                    ValueRole.Argument(call, argument),
                )
                .value()
        private val edge = ValueTransfer.fromCompiler(base.producer, site, ValueTransferKind.ARGUMENT).value()
        private val origin =
            RepresentationRule.Origin.admit(rule("origin"), bind(base.owner, ModelValuePosition.Result), state).value()
        val arrival =
            RepresentationEvidence.origin(base.producer, base.producerWitness.invocation, origin)
                .value()
                .transfer(edge)
                .value()
        private val observations =
            listOf(
                base.observe(base.producer, listOf(edge)),
                base.observe(
                    site,
                    emptyList(),
                    listOf(ValueFlowObligation(site, ValueFlowUnsupportedCause.UNMODELED_CALL)),
                ),
            )

        fun rule(name: String) = ModelRuleReference(identity, id(name))

        fun path(terminal: QueryImpactTerminal) =
            QueryImpactPath.fromEvidence(
                    base.producer,
                    listOf(QueryImpactStep.Compiler(edge)),
                    QueryImpactRepresentation.Present(arrival),
                    terminal,
                )
                .value()

        val resultObservation = listOf(base.observe(call.resultSite(), emptyList()))

        fun modeledPath(rule: RepresentationRule): QueryImpactPath {
            val next =
                when (rule) {
                    is RepresentationRule.Transfer -> arrival.modeledTransfer(call.resultSite(), call, rule).value()
                    is RepresentationRule.Transformation -> arrival.transform(call.resultSite(), call, rule).value()
                    is RepresentationRule.Origin,
                    is RepresentationRule.ConsumerExpectation -> error("Expected outgoing model")
                }
            val application = next.branches.single().history.last() as RepresentationModelApplication
            return QueryImpactPath.fromEvidence(
                    base.producer,
                    listOf(QueryImpactStep.Compiler(edge), QueryImpactStep.ModeledRepresentation(application)),
                    QueryImpactRepresentation.Present(next),
                    QueryImpactTerminal.SupportedDomainEnd.admit(resultObservation.single()).value(),
                )
                .value()
        }

        fun ledger(
            rules: List<RepresentationRule>,
            paths: List<QueryImpactPath>,
            extra: List<io.github.amichne.kast.relation.contract.ValueFlowStep> = emptyList(),
        ) =
            QueryImpactLedger.fromEvidence(
                listOf(base.producer),
                base.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                listOf(origin) + rules,
                emptyList(),
                observations + extra,
                paths,
                originalProducers = listOf(base.producerWitness),
            )

        private fun bind(endpoint: RelationEndpoint, position: ModelValuePosition) =
            ExactModelCallablePosition.admit(
                    ModelCallableReference(
                        endpoint.lease.identity,
                        endpoint.compilerIdentity,
                        endpoint.file,
                        endpoint.range,
                        position,
                    ),
                    RevalidatedRelationEndpoint.validate(endpoint, (endpoint as RelationEndpoint.Resolved).evidence)
                        .value(),
                )
                .value()
    }
}

private fun id(raw: String) = ModelIdentifier.parse(raw).value()

private fun <V, F> Refinement<V, F>.value(): V = assertInstanceOf<Refinement.Refined<V>>(this).value
