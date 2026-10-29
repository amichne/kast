package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactMapInputConservationTest {
    @Test
    fun `an omitted input edge cannot admit a complete retained route`() {
        val f = InputLedgerFixture()
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_REVIEWED_STEP),
            f.ledger(listOf(f.path), listOf(f.base.observe(f.input.source)) + f.observations.drop(1)),
        )
    }

    @Test
    fun `a reviewed input and output cannot erase an unresolved sibling`() {
        val f = InputLedgerFixture()
        val obligation = ValueFlowObligation(f.input.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
        val observed = f.base.observe(f.input.source, listOf(f.input), listOf(obligation))
        val observations = listOf(observed) + f.observations.drop(1)
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
            f.ledger(listOf(f.path), observations),
        )
        val unresolved =
            QueryImpactPath.fromEvidence(
                    f.input.source,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(obligation),
                )
                .inputValue()
        assertTrue(
            f.ledger(listOf(f.path, unresolved), observations).inputValue().closure is QueryImpactClosure.Unresolved
        )
    }

    @Test
    fun `whole content remains derived after the compiler return and exact output`() {
        val f = InputLedgerFixture()
        val ledger = f.ledger(listOf(f.path), f.observations).inputValue()
        assertEquals(QueryImpactClassification.Established(QueryImpactRelationship.DERIVED), f.path.classification)
        val count = QueryImpactWitnessView.count(ledger, QueryImpactWitnessSection.NATIVE_READS).inputValue()
        val entries =
            QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.NATIVE_READS, 0, count.value)
                .inputValue()
                .entries
        assertEquals(2, entries.count { it.evidence is QueryImpactWitnessEntry.ReviewedTransfer })
        assertEquals(1, entries.count { it.evidence is QueryImpactWitnessEntry.CompilerTransfer })
        assertTrue(ledger.closure is QueryImpactClosure.Unresolved, "A site-only seed still lacks producer identity")
    }

    @Test
    fun `selected element keeps exact classification and distinct retained input and output proofs`() {
        val f = InputLedgerFixture(element = true)
        f.ledger(listOf(f.path), f.observations).inputValue()
        assertEquals(QueryImpactClassification.Established(QueryImpactRelationship.EXACT), f.path.classification)
        val graph = QueryImpactRetainedGraph()
        assertTrue(f.input.storageBytes(graph) > 8L)
        assertTrue(f.output.storageBytes(graph) > 8L)
        assertEquals(8L, f.input.storageBytes(graph))
        assertEquals(8L, f.output.storageBytes(graph))
    }
}

private class InputLedgerFixture(element: Boolean = false) {
    val base = ReviewedLedgerFixture()
    private val binding = base.binding
    private val owner = binding.enclosing as RelationEndpoint.Resolved
    private val selected = RelationEndpoint.resolve(owner.lease, owner.scope, binding.selected.target).inputValue()
    private val call = ValueInvocation.fromCompiler(owner, binding.occurrence.range, selected).inputValue()
    private val formal =
        ValueFormalParameter.fromCompiler(
                owner,
                ValueArgumentPosition.parse(0).inputValue(),
                SymbolDiscoveryCandidateName.parse("item").inputValue(),
                inputRange(31, 35),
                ValueExecutionOwner.reviewedCallback(binding),
            )
            .inputValue()
    private val receiver =
        ValueSite.fromCompiler(
                owner,
                inputRange(20, 25),
                ValueRole.Receiver(call, ValueReceiverKind.EXTENSION),
                valuePath =
                    if (element) ValueAccessPath.fromSegments(listOf(ValuePathSegment.Element)).inputValue()
                    else ValueAccessPath.Whole,
            )
            .inputValue()
    val input = ReviewedValueTransfer.callbackInput(receiver, binding, formal).inputValue()
    private val returned =
        ValueSite.fromCompiler(
                owner,
                inputRange(40, 50),
                ValueRole.Return,
                formal.executionOwner,
                valuePath = input.target.valuePath,
            )
            .inputValue()
    private val compiler = ValueTransfer.fromCompiler(input.target, returned, ValueTransferKind.RETURN).inputValue()
    val output = ReviewedValueTransfer.callbackResult(returned, binding).inputValue()
    val observations =
        listOf(
            base.observe(receiver, listOf(input)),
            ValueFlowStep.fromEvidence(
                    input.target,
                    listOf(compiler),
                    emptyList(),
                    ValueFlowTerminal.SupportedDomainExhausted,
                    base.domain,
                    RelationWorkCount.parse(1).inputValue(),
                    reviewedTransfers = emptyList(),
                )
                .inputValue(),
            base.observe(returned, listOf(output)),
            base.observe(output.target),
        )
    val path =
        QueryImpactPath.fromEvidence(
                receiver,
                listOf(
                    QueryImpactStep.ReviewedLibrary(input),
                    QueryImpactStep.Compiler(compiler),
                    QueryImpactStep.ReviewedLibrary(output),
                ),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.SupportedDomainEnd.admit(observations.last()).inputValue(),
            )
            .inputValue()

    fun ledger(paths: List<QueryImpactPath>, observations: List<ValueFlowStep>) =
        QueryImpactLedger.fromEvidence(
            listOf(receiver),
            base.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            observations,
            paths,
        )
}

private fun inputRange(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).inputValue()

private fun <V, F> Refinement<V, F>.inputValue(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid map input fixture: $failure")
    }
