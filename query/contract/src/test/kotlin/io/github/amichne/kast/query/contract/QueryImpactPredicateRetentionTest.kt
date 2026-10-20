package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalOrigin
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicateSubject
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryImpactPredicateRetentionTest {
    @Test
    fun `site storage visits the exact tested value and resolved class proof`() {
        val f = PredicateRetentionFixture()
        val subject = ValuePredicateSubject.fromCompiler(f.base.first)
        val predicate = ValuePredicate.Subtype.fromCompiler(subject, f.type).value()
        val predicates = ValuePredicates.fromCompiler(listOf(predicate)).value()
        val guarded = f.base.destination.withState(predicates = predicates).value()
        val graph = QueryImpactRetainedGraph()
        guarded.storageBytes(graph)

        assertEquals(8L, graph.node(predicates) { error("Predicate conjunction was not visited") })
        assertEquals(8L, graph.node(predicates.entries) { error("Predicate set was not visited") })
        assertEquals(8L, graph.node(predicate) { error("Subtype proof was not visited") })
        assertEquals(8L, graph.node(subject) { error("Tested value snapshot was not visited") })
        assertEquals(8L, graph.node(subject.occurrence) { error("Exact tested value was not visited") })
        assertEquals(8L, graph.node(f.type) { error("Resolved class was not visited") })
    }

    @Test
    fun `site identity storage visits the nonnull predicate independently of the enclosing site`() {
        val f = PredicateRetentionFixture()
        val subject = ValuePredicateSubject.fromCompiler(f.base.first)
        val predicate = ValuePredicate.NonNull.fromCompiler(subject)
        val predicates = ValuePredicates.fromCompiler(listOf(predicate)).value()
        val identity = f.base.destination.withState(predicates = predicates).value().identity
        val graph = QueryImpactRetainedGraph()
        identity.storageBytes(graph)

        assertEquals(8L, graph.node(predicates) { error("Identity conjunction was not visited") })
        assertEquals(8L, graph.node(predicate) { error("NonNull proof was not visited") })
        assertEquals(8L, graph.node(subject.occurrence) { error("Predicate occurrence was not visited") })
        assertEquals(ValuePredicates.Empty, subject.occurrence.predicates)
    }

    @Test
    fun `a native predicate selection remains exact rather than selecting the unguarded occurrence`() {
        val f = PredicateRetentionFixture()
        val predicate = ValuePredicate.NonNull.fromCompiler(ValuePredicateSubject.fromCompiler(f.base.first))
        val guarded =
            f.base.destination.withState(predicates = ValuePredicates.fromCompiler(listOf(predicate)).value()).value()
        val selection = QueryImpactSiteSelection.fromNative(guarded)
        assertEquals(false, selection.matches(f.base.destination))
        assertEquals(true, selection.matches(guarded))
    }

    @Test
    fun `implicit formal storage preserves its anonymous execution and honest origin`() {
        val f = PredicateRetentionFixture()
        val parameter = f.implicitParameter()
        val graph = QueryImpactRetainedGraph()
        parameter.storageBytes(graph)

        assertEquals(8L, graph.node(parameter.executionOwner) { error("Formal execution owner was not visited") })
        assertEquals(8L, graph.node(parameter.origin) { error("Implicit origin was not visited") })
        assertEquals(ValueFormalOrigin.Implicit, parameter.origin)
        assertEquals(f.range(40, 80), parameter.range)
    }
}

private class PredicateRetentionFixture {
    val base = QueryImpactLedgerFixture()
    val type =
        RelationEndpoint.resolve(
                base.lease,
                base.scope,
                CompilerGroundedSymbolEvidence.fromBoundary(
                        base.file,
                        300,
                        340,
                        "PredicateType",
                        "fixture.PredicateType",
                        CompilerSymbolKind.CLASSLIKE,
                        CanonicalCompilerSignature.classLike("fixture.PredicateType").value(),
                    )
                    .value(),
            )
            .value()

    fun implicitParameter(): ValueFormalParameter {
        val bodyRange = range(40, 80)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(base.file, bodyRange),
                    null,
                    emptyList(),
                    listOf("String"),
                    0,
                )
                .value() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(base.file, bodyRange, signature).value()
        val execution = ValueExecutionOwner.fromCompiler(base.owner, body, base.lease.identity).value()
        return ValueFormalParameter.fromCompilerImplicit(
                execution,
                ValueArgumentPosition.parse(0).value(),
                SymbolDiscoveryCandidateName.parse("it").value(),
            )
            .value()
    }

    fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
