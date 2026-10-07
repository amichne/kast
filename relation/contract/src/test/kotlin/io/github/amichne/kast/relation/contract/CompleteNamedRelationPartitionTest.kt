package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CompleteNamedRelationPartitionTest {
    private val fixture = CallbackInvocationFlowFixture()
    private val owner = MovingLiveReadAuthorityFixture(fixture.root)
    private val budget =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(8).value(),
                WorkUnitLimit.parse(32).value(),
                ElapsedTimeLimitMillis.parse(1000).value(),
            ),
            RelationByteLimit.parse(100000).value(),
        )

    @Test
    fun `fresh and reused forward and reverse named partitions have identical current facts`() {
        for (meaning in listOf(RelationMeaning.Callees, RelationMeaning.Callers)) {
            val previous = complete(owner.admit(), meaning)
            val partition = CompleteNamedRelationPartition.fromCompiler(previous).value()
            val authority = owner.advance()
            val fresh = complete(authority, meaning)
            val endpoints = partition.endpoints.associateWith { old -> live(authority, old) }
            val restored =
                CallbackEndpointReadmissions.fromCompiler(authority, endpoints)
                    .value()
                    .readmitNamedRelations(partition, fresh.batch.request, RelationWorkCount.parse(3).value())
                    .value()
            assertEquals(fresh.batch.facts, restored.batch.facts)
            assertSame(fresh.batch.request, restored.batch.request)
            assertSame(fresh.batch.request.subject, restored.batch.facts.single().subject)
            assertEquals(previous.batch.facts.single().occurrence, restored.batch.facts.single().occurrence)
            assertEquals(3L, restored.batch.examinedWorkUnits.value)
        }
    }

    @Test
    fun `empty adjacency still needs current compiler subject proof`() {
        val old = complete(owner.admit(), RelationMeaning.Callers, empty = true)
        val partition = CompleteNamedRelationPartition.fromCompiler(old).value()
        val authority = owner.advance()
        val request = complete(authority, RelationMeaning.Callers, empty = true).batch.request
        val proof = CallbackEndpointReadmissions.fromCompiler(authority, emptyMap()).value()
        assertEquals(
            NamedRelationReadmissionFailure.Endpoint(
                CallbackSummaryReadmissionFailure.MissingEndpoint(partition.subject.valueIdentity)
            ),
            proof.readmitNamedRelations(partition, request, RelationWorkCount.parse(0).value()).failure(),
        )
    }

    @Test
    fun `restored adjacency obeys new byte and work grants`() {
        val old = complete(owner.admit(), RelationMeaning.Callees)
        val partition = CompleteNamedRelationPartition.fromCompiler(old).value()
        val authority = owner.advance()
        val subject = live(authority, old.batch.request.subject)
        val endpoints =
            CallbackEndpointReadmissions.fromCompiler(
                    authority,
                    partition.endpoints.associateWith { live(authority, it) },
                )
                .value()
        val small =
            RelationRequest.start(
                subject,
                RelationMeaning.Callees,
                budget.copy(returnedBytes = RelationByteLimit.parse(1).value()),
            )
        assertEquals(
            NamedRelationReadmissionFailure.Batch(RelationBatchFailure.BYTE_LIMIT_EXCEEDED),
            endpoints.readmitNamedRelations(partition, small, RelationWorkCount.parse(3).value()).failure(),
        )
        val work = RelationRequest.start(subject, RelationMeaning.Callees, budget)
        assertEquals(
            NamedRelationReadmissionFailure.Batch(RelationBatchFailure.WORK_LIMIT_EXCEEDED),
            endpoints.readmitNamedRelations(partition, work, RelationWorkCount.parse(33).value()).failure(),
        )
    }

    @Test
    fun `smaller result grant rejects before endpoint enumeration`() {
        val old = complete(owner.admit(), RelationMeaning.Callees, count = 2)
        val partition = CompleteNamedRelationPartition.fromCompiler(old).value()
        val authority = owner.advance()
        val subject = live(authority, old.batch.request.subject)
        val request =
            RelationRequest.start(
                subject,
                RelationMeaning.Callees,
                budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(1).value())),
            )
        val endpoints = CallbackEndpointReadmissions.fromCompiler(authority, emptyMap()).value()
        assertEquals(
            NamedRelationReadmissionFailure.Batch(RelationBatchFailure.RESULT_LIMIT_EXCEEDED),
            endpoints.readmitNamedRelations(partition, request, RelationWorkCount.parse(0).value()).failure(),
        )
    }

    @Test
    fun `mixed evidence and another relation family are never filtered into complete named absence`() {
        val empty = complete(owner.admit(), RelationMeaning.Callees, empty = true)
        val omitted =
            empty.batch
                .withOmissions(
                    listOf(
                        RelationOmissionEvidence.unmeasured(
                            empty.batch.request.providerCursor.provider,
                            RelationLimitation.UNSUPPORTED_ITEM,
                        )
                    )
                )
                .value()
        assertEquals(
            NamedRelationPartitionFailure.NON_NAMED_EVIDENCE,
            CompleteNamedRelationPartition.fromCompiler(RelationCompilation.complete(omitted)).failure(),
        )
        val references = complete(owner.admit(), RelationMeaning.References, empty = true)
        assertEquals(
            NamedRelationPartitionFailure.UNSUPPORTED_MEANING,
            CompleteNamedRelationPartition.fromCompiler(references).failure(),
        )
    }

    private fun complete(
        authority: LiveSemanticReadAuthority,
        meaning: RelationMeaning,
        empty: Boolean = false,
        count: Int = 1,
    ): RelationCompilation.Complete {
        val caller = live(authority, fixture.caller)
        val target = live(authority, fixture.target)
        val subject = if (meaning == RelationMeaning.Callees) caller else target
        val request = RelationRequest.start(subject, meaning, budget)
        val facts =
            if (empty) emptyList()
            else
                List(count) { index ->
                    RelationFact.create(
                            request,
                            caller,
                            target,
                            fixture.occurrence(20 + index, 80 + index),
                            RelationProvenance.K2_AUTHORED_SOURCE,
                        )
                        .value()
                }
        val bytes =
            RelationByteCount.parse(facts.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() })
                .value()
        return RelationCompilation.complete(
            RelationBatch.create(
                    request,
                    facts,
                    bytes,
                    RelationWorkCount.parse(1).value(),
                    RelationResultCount.parse(facts.size).value(),
                )
                .value()
        )
    }

    private fun live(authority: LiveSemanticReadAuthority, endpoint: RelationEndpoint): RelationEndpoint.Resolved {
        val resolved = endpoint as RelationEndpoint.Resolved
        return RelationEndpoint.resolve(authority, resolved.scope, resolved.evidence, resolved.constraints).value()
    }
}

private fun <V, F> Refinement<V, F>.value(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

private fun <V, F> Refinement<V, F>.failure(): F =
    when (this) {
        is Refinement.Refined -> error("Expected rejection")
        is Refinement.Rejected -> failure
    }
