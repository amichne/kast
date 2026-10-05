package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackExclusionReason
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.traversal.contract.TraversalCallbackObservation
import io.github.amichne.kast.traversal.contract.TraversalCallbackObservationFailure
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TraversalCallbackObservationTest {
    private val fixture = TraversalTestFixture()
    private val owner = fixture.selector("owner", 10)
    private val target = fixture.selector("target", 30)

    @Test
    fun `excluded callback is retained with frontier identity and never becomes a named edge`() {
        val plan = fixture.plan(owner)
        val operations =
            traversalOperations(RelationOperations { request -> callbackResult(request) }, TraversalNanoClock { 0L })
        val result = assertInstanceOf(TraversalResult.Complete::class.java, runSuspend { operations.run(plan) })
        assertTrue(result.page.records.isEmpty())
        assertEquals(owner.fingerprint.value, result.page.callbackObservations.single().entry.node.fingerprint.value)
        assertEquals(
            target.compilerIdentity,
            result.page.callbackObservations.single().observation.target.compilerIdentity,
        )
        assertTrue(result.page.scopeExclusions.isEmpty())
    }

    @Test
    fun `callback frontier admission rejects changed subject and domain`() {
        val plan = fixture.plan(owner)
        val request = RelationRequest.start(owner, RelationMeaning.Callees, fixture.relationBudget())
        val excluded = callbackResult(request).batch.callbackObservations.single()
        val entry =
            TraversalFrontierEntry.create(plan, TraversalNode.start(owner), TraversalDepth.parse(0).refined()).refined()
        assertTrue(TraversalCallbackObservation.create(plan, entry, excluded) is Refinement.Refined)
        val other = fixture.plan(target)
        val otherEntry =
            TraversalFrontierEntry.create(other, TraversalNode.start(target), TraversalDepth.parse(0).refined())
                .refined()
        assertEquals(
            TraversalCallbackObservationFailure.SUBJECT_MISMATCH,
            (TraversalCallbackObservation.create(other, otherEntry, excluded) as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `callback frontier admission preserves authority and requested domain`() {
        val request = RelationRequest.start(owner, RelationMeaning.Callees, fixture.relationBudget())
        val observed = callbackResult(request).batch.callbackObservations.single()
        val expanded =
            TraversalPlan.start(
                    owner,
                    RelationMeaning.Callees,
                    fixture.plan(owner).budget,
                    expansion = RelationSearchBoundary.WORKSPACE_EXPANSION,
                )
                .refined()
        val entry = TraversalFrontierEntry.create(expanded, TraversalNode.start(owner), TraversalDepth.Zero).refined()
        assertEquals(
            TraversalCallbackObservationFailure.DOMAIN_MISMATCH,
            (TraversalCallbackObservation.create(expanded, entry, observed) as Refinement.Rejected).failure,
        )
        val changedAuthority =
            io.github.amichne.kast.workspace.contract.SemanticReadLease(
                fixture.lease.workspaceRoot,
                io.github.amichne.kast.kernel.EvidenceGeneration.parse(32L).refined(),
            )
        val changed =
            io.github.amichne.kast.symbol.contract.SymbolSelector.issue(
                changedAuthority,
                owner.scope,
                io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromSelector(owner),
            )
        val changedPlan = fixture.plan(changed)
        val changedEntry =
            TraversalFrontierEntry.create(changedPlan, TraversalNode.start(changed), TraversalDepth.Zero).refined()
        assertEquals(
            TraversalCallbackObservationFailure.AUTHORITY_MISMATCH,
            (TraversalCallbackObservation.create(changedPlan, changedEntry, observed) as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `inline named edge and its callback proof fit a one record traversal page atomically`() {
        val plan = fixture.plan(owner, aggregateRecords = 1, oneHop = fixture.relationBudget(records = 1))
        val operations =
            traversalOperations(
                RelationOperations { request -> callbackResult(request, inline = true) },
                TraversalNanoClock { 0L },
            )
        val result = assertInstanceOf(TraversalResult.Qualified::class.java, runSuspend { operations.run(plan) })
        assertEquals(1, result.page.records.size)
        assertEquals(1, result.page.callbackObservations.size)
        assertEquals(
            result.page.records.single().fact.occurrence,
            result.page.callbackObservations.single().observation.occurrence,
        )
        assertSame(CallbackNamedCallPolicy.AdmittedInline, result.page.callbackObservations.single().observation.policy)
    }

    @Test
    fun `self cycle retains its edge without rereading seed callback above depth zero`() {
        val requests = mutableListOf<RelationRequest>()
        val plan = fixture.plan(owner, depth = 3)
        val operations =
            traversalOperations(
                RelationOperations { request ->
                    requests += request
                    cyclicResult(request, listOf(owner))
                },
                TraversalNanoClock { 0L },
            )
        val result = assertInstanceOf(TraversalResult.Complete::class.java, runSuspend { operations.run(plan) })
        assertEquals(listOf(owner.fingerprint.value), requests.map { it.subject.fingerprint.value })
        assertEquals(listOf(1), result.page.records.map { it.depth.value })
        assertEquals(owner.fingerprint.value, result.page.records.single().related.fingerprint.value)
        assertEquals(listOf(0), result.page.callbackObservations.map { it.entry.depth.value })
        assertEquals(owner.fingerprint.value, result.page.callbackObservations.single().entry.node.fingerprint.value)
    }

    @Test
    fun `two node cycle retains return to seed edge but callback seed is still read only at depth zero`() {
        val requests = mutableListOf<RelationRequest>()
        val plan = fixture.plan(owner, depth = 3)
        val operations =
            traversalOperations(
                RelationOperations { request ->
                    requests += request
                    cyclicResult(
                        request,
                        listOf(if (request.subject.fingerprint.value == owner.fingerprint.value) target else owner),
                    )
                },
                TraversalNanoClock { 0L },
            )
        val result = assertInstanceOf(TraversalResult.Complete::class.java, runSuspend { operations.run(plan) })
        assertEquals(
            listOf(owner.fingerprint.value, target.fingerprint.value),
            requests.map { it.subject.fingerprint.value },
        )
        assertEquals(listOf(1, 2), result.page.records.map { it.depth.value })
        assertEquals(owner.fingerprint.value, result.page.records.last().related.fingerprint.value)
        assertEquals(listOf(0, 1), result.page.callbackObservations.map { it.entry.depth.value })
        assertEquals(
            listOf(owner.fingerprint.value, target.fingerprint.value),
            result.page.callbackObservations.map { it.entry.node.fingerprint.value },
        )
    }

    private fun cyclicResult(
        request: RelationRequest,
        targets: List<io.github.amichne.kast.symbol.contract.SymbolSelector>,
    ): RelationReadResult.Complete {
        val subject = request.subject
        val start = subject.range.startInclusive
        val callback =
            RelationCallbackObservation.fromNativeBoundary(
                    request,
                    RelationOccurrence.fromBoundary(subject.file, start + 2, start + 3).refined(),
                    fixture.endpoint(subject, target).evidence,
                    io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence.fromSelector(
                        if (subject.fingerprint.value == owner.fingerprint.value) owner else target
                    ),
                    RelationOccurrence.fromBoundary(subject.file, start + 1, start + 4).refined(),
                    CallbackNamedCallPolicy.Excluded(
                        CallbackExclusionReason.NON_INLINE_ARGUMENT,
                        RelationOccurrence.fromBoundary(subject.file, start + 1, start + 4).refined(),
                    ),
                    CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING),
                )
                .refined()
        val facts = fixture.completeRelationResult(request, targets.map { fixture.endpoint(subject, it) }).batch.facts
        val bytes =
            callback.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() +
                facts.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
        val batch =
            RelationBatch.create(
                    request,
                    facts,
                    RelationByteCount.parse(bytes).refined(),
                    RelationWorkCount.parse(1L).refined(),
                    RelationResultCount.parse(facts.size).refined(),
                    callbackObservations = listOf(callback),
                )
                .refined()
        return RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
    }

    private fun callbackResult(request: RelationRequest, inline: Boolean = false): RelationReadResult.Complete {
        val exclusion =
            RelationCallbackObservation.fromNativeBoundary(
                    request,
                    RelationOccurrence.fromBoundary(owner.file, 12, 13).refined(),
                    fixture.endpoint(request.subject, target).evidence,
                    fixture.endpoint(request.subject, owner).evidence,
                    RelationOccurrence.fromBoundary(owner.file, 11, 15).refined(),
                    if (inline) CallbackNamedCallPolicy.AdmittedInline
                    else
                        CallbackNamedCallPolicy.Excluded(
                            CallbackExclusionReason.NON_INLINE_ARGUMENT,
                            RelationOccurrence.fromBoundary(owner.file, 11, 15).refined(),
                        ),
                    CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING),
                )
                .refined()
        val facts =
            if (inline)
                listOf(
                    RelationFact.create(
                            request,
                            request.subject,
                            fixture.endpoint(request.subject, target),
                            exclusion.occurrence,
                            RelationProvenance.K2_AUTHORED_SOURCE,
                        )
                        .refined()
                )
            else emptyList()
        val bytes =
            exclusion.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() +
                facts.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
        val batch =
            RelationBatch.create(
                    request,
                    facts,
                    RelationByteCount.parse(bytes).refined(),
                    RelationWorkCount.parse(1L).refined(),
                    RelationResultCount.parse(facts.size).refined(),
                    callbackObservations = listOf(exclusion),
                )
                .refined()
        return RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
