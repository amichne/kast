package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Detached publication/storage facts, without native compiler or PSI execution. */
class CallbackSummaryReuseTest {
    private val fixture = RelationReadTest()
    private val request = fixture.request(RelationMeaning.Callees)
    private val target = fixture.fact(request).target as RelationEndpoint.Resolved
    private val formal =
        CallbackParameterIdentity.fromCompiler(
                target,
                ValueArgumentPosition.parse(0).refined(),
                RelationOccurrence.fromBoundary(target.file, 72, 73).refined(),
            )
            .refined()

    @Test
    fun `summary observations distinguish a miss successful instantiation and rejected supplier`() {
        val counts = SummaryCounts()
        val summaries = CallbackParameterSummaries(request.budget, counts)
        val reuse = CallbackSummaryReuse(summaries)
        val body = supplierFlow(72).body
        val binding =
            CallbackBindingEvidence.Bound(
                CallbackArgumentBinding.fromCompiler(
                        ValueInvocation.fromCompiler(target, body.range, target).refined(),
                        RelationCallableBody.Named.fromCompiler(target.evidence).refined(),
                        formal.position,
                        formal.parameter,
                    )
                    .refined()
            )
        assertEquals(CallbackSummaryRestore.Missing, reuse.restore(formal, body, binding).refined())
        val candidate =
            reuse.capture(formal, emptyList(), emptySet(), emptyList(), CallbackInvocationScan.EXHAUSTIVE).refined()
                as CallbackSummaryCandidate.Admitted
        val flow = candidate.summary.instantiate(body, binding).refined()
        reuse.publish(CallbackInvocationFlowRead.Observed(flow), emptyList(), candidate)
        assertEquals(CallbackSummaryRestore.Reused(flow), reuse.restore(formal, body, binding).refined())
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowFailure.UNBOUND_INVOCATION),
            reuse.restore(
                formal,
                body,
                CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
            ),
        )
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_SUMMARY_MISSES,
                IntellijReadCounter.CALLBACK_SUMMARIES_RETAINED,
                IntellijReadCounter.CALLBACK_SUMMARY_HITS,
                IntellijReadCounter.CALLBACK_SUMMARY_REJECTIONS,
            ),
            counts.values,
        )
    }

    @Test
    fun `optional storage rejection cannot downgrade a flow admitted with supplier obligations`() {
        val counts = SummaryCounts()
        val sizing = CallbackSummaryReuse(CallbackParameterSummaries(request.budget))
        val candidate =
            sizing.capture(formal, emptyList(), emptySet(), emptyList(), CallbackInvocationScan.EXHAUSTIVE).refined()
                as CallbackSummaryCandidate.Admitted
        val summaries =
            CallbackParameterSummaries(
                RelationBudget(
                    request.budget.resources,
                    RelationByteLimit.parse(candidate.summary.retainedBytes).refined(),
                ),
                counts,
            )
        summaries.retention.admit(1L).refined()
        val reuse = CallbackSummaryReuse(summaries)
        val supplied = supplierFlow()
        val published = reuse.publish(CallbackInvocationFlowRead.Observed(supplied), emptyList(), candidate)
        assertEquals(CallbackInvocationFlowRead.Observed(supplied), published)
        assertEquals(setOf(CallbackInvocationFlowCause.STORED_CALLBACK), supplied.obligations)
        assertNull(summaries.find(formal))
        assertEquals(emptySet<CallbackInvocationFlowCause>(), candidate.summary.obligations)
        assertEquals(listOf(IntellijReadCounter.CALLBACK_SUMMARY_RETENTION_REJECTIONS), counts.values)
    }

    @Test
    fun `supplier evidence cannot contaminate an exhaustive formal snapshot`() {
        val summaries = CallbackParameterSummaries(request.budget)
        val reuse = CallbackSummaryReuse(summaries)
        val candidate =
            reuse.capture(formal, emptyList(), emptySet(), emptyList(), CallbackInvocationScan.EXHAUSTIVE).refined()
                as CallbackSummaryCandidate.Admitted
        val supplied = supplierFlow()
        reuse.publish(CallbackInvocationFlowRead.Observed(supplied), emptyList(), candidate)
        assertEquals(emptySet<CallbackInvocationFlowCause>(), requireNotNull(summaries.find(formal)).obligations)
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_SCAN_PROOF),
            reuse.capture(formal, emptyList(), supplied.obligations, emptyList(), CallbackInvocationScan.EXHAUSTIVE),
        )
        assertEquals(candidate.summary, summaries.find(formal))
    }

    private fun supplierFlow(start: Int = 12): CallbackInvocationFlow {
        val range = RelationOccurrence.fromBoundary(request.subject.file, start, start + 4).refined().range
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(request.subject.file, range),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .refined() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(request.subject.file, range, signature).refined()
        return CallbackInvocationFlow.fromCompiler(
                request.subject.lease.identity,
                body,
                CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                emptyList(),
                setOf(CallbackInvocationFlowCause.STORED_CALLBACK),
            )
            .refined()
    }
}

private class SummaryCounts : IntellijReadObservation {
    val values = mutableListOf<IntellijReadCounter>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        assertEquals(IntellijReadContributor.NONE, contributor)
        repeat(amount) { values += counter }
    }

    override fun terminated(
        reason: io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination,
        contributor: IntellijReadContributor,
    ) = error("Unexpected summary termination: $reason")
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
