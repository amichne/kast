package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpointStorageAdmission
import io.github.amichne.kast.query.contract.QueryCheckpointStorageObservation
import io.github.amichne.kast.query.contract.QueryCheckpointStorageOutcome
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCheckpointStorageObservationTest {
    @Test
    fun `empty discovery checkpoint charges its existing root and accounted seed structure`() {
        val fixture = QueryServiceTest()
        val request = fixture.request(fixture.symbolPlan(), 8L)
        val checkpoint =
            PipelineCheckpoint(
                PipelineSeed.Accounted.create(request.plan),
                request.lease,
                emptyList(),
                emptyMap(),
                QueryJoinSnapshot(emptyMap()),
                emptySet(),
                0.queryCount(),
            )
        val estimate = checkpoint.storageEstimate()
        // Checkpoint container + seed + discover payload/task + owner-ledger node and incoming reference.
        val expected = 4096L + 256L + 4096L + 512L + 512L + 8L
        assertEquals(expected, checkpoint.retainedBytes)
        assertEquals(expected, estimate.tasks.value)
        assertEquals(0L, estimate.identityRows.value)
        assertEquals(0L, estimate.inputs.value)
        assertEquals(0L, estimate.impact.value)
        assertEquals(0L, estimate.joins.value)
    }

    @Test
    fun `capacity rejection preserves the exact limit and emits one component snapshot`() = runTest {
        val (result, admission) = boundedPage(1L)
        assertEquals(QueryCheckpointStorageOutcome.CAPACITY_EXCEEDED, admission.outcome)
        assertEquals(1L, admission.allowance.value)
        assertTrue(admission.estimate.required.value > 1L)
        val terminal = assertInstanceOf(QueryContinuationState.Terminal::class.java, result.continuation)
        assertEquals(QueryTerminalReason.CHECKPOINT_CAPACITY_EXCEEDED, terminal.reason)
        assertEquals(1, result.result.symbolRows().size)
    }

    @Test
    fun `admitted checkpoint retains the same estimated bytes and original plan and authority`() = runTest {
        val (result, admission) = boundedPage(QueryByteLimit.DefaultCheckpoint.value)
        assertEquals(QueryCheckpointStorageOutcome.WITHIN_LIMIT, admission.outcome)
        val retained = assertInstanceOf(QueryContinuationState.Resumable::class.java, result.continuation)
        val checkpoint = retained.checkpoint as PipelineCheckpoint
        assertEquals(checkpoint.retainedBytes, admission.estimate.required.value)
        assertEquals(checkpoint.storageEstimate(), admission.estimate)
        assertEquals(QueryByteLimit.DefaultCheckpoint, admission.allowance)
        assertEquals(1, result.result.symbolRows().size)
    }

    @Test
    fun `completed query never manufactures a checkpoint admission`() = runTest {
        val fixture = QueryServiceTest()
        val result =
            fixture
                .service(
                    clock = QueryNanoClock { 0L },
                    checkpointObservation = QueryCheckpointStorageObservation { error("Unexpected checkpoint") },
                )
                .run(fixture.request(fixture.symbolPlan(), 8L))
        assertInstanceOf(QueryExecutionResult.Complete::class.java, result)
    }

    @Test
    fun `successive pages reuse the admitted seed and preserve exact descriptions in order`() = runTest {
        val fixture = QueryServiceTest()
        val first = fixture.selector(fixture.selection())
        val selectors = listOf(first, otherSelector(fixture, first), otherSelector(fixture, first, "ThirdService", 80))
        val plan = fixture.exactReferencePlan(selectors)
        val original = fixture.request(plan, 8L, resultLimit = 1)
        val described = mutableListOf<SymbolSelector>()
        val service =
            fixture.service(
                clock = QueryNanoClock { 0L },
                exact =
                    fixture.exactOperations(
                        describe = {
                            assertTrue(described.size < selectors.size, "Unexpected exact description")
                            assertSame(selectors[described.size], it)
                            described += it
                            SymbolDescriptionResult.Described(SymbolDescription.from(it))
                        },
                        resolve = { error("Unexpected resolution") },
                    ),
            )
        val page1 = assertInstanceOf(QueryExecutionResult.Qualified::class.java, service.run(original))
        val checkpoint1 = (page1.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        val page2 =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                service.run(QueryExecutionRequest.create(plan, original.lease, original.budget, checkpoint1).refined()),
            )
        val checkpoint2 = (page2.continuation as QueryContinuationState.Resumable).checkpoint as PipelineCheckpoint
        assertSame(checkpoint1.seed, checkpoint2.seed)
        assertSame(plan, checkpoint2.plan)
        assertSame(original.lease, checkpoint2.lease)
        val page3 =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                service.run(QueryExecutionRequest.create(plan, original.lease, original.budget, checkpoint2).refined()),
            )
        assertEquals(selectors, described, "Unconsumed exact descriptions")
        assertEquals(
            selectors,
            listOf(page1.result, page2.result, page3.result).flatMap { it.symbolRows().map { row -> row.selector } },
        )
        assertTrue(listOf(page1.result, page2.result, page3.result).all { it.failures.isEmpty() })
    }

    private suspend fun boundedPage(
        allowance: Long
    ): Pair<QueryExecutionResult.Qualified, QueryCheckpointStorageAdmission> {
        val fixture = QueryServiceTest()
        val selector = fixture.selector(fixture.selection())
        val plan = fixture.exactReferencePlan(listOf(selector, otherSelector(fixture, selector)))
        val original = fixture.request(plan, 8L, resultLimit = 1)
        val request =
            QueryExecutionRequest.create(
                    plan,
                    original.lease,
                    original.budget.copy(checkpointBytes = QueryByteLimit.parse(allowance).refined()),
                )
                .refined()
        val observations = mutableListOf<QueryCheckpointStorageAdmission>()
        var describes = 0
        val service =
            fixture.service(
                clock = QueryNanoClock { 0L },
                exact =
                    fixture.exactOperations(
                        describe = {
                            assertEquals(0, describes++, "Unexpected or excess exact description")
                            assertEquals(selector, it)
                            SymbolDescriptionResult.Described(SymbolDescription.from(it))
                        },
                        resolve = { error("Unexpected exact resolution") },
                    ),
                checkpointObservation =
                    QueryCheckpointStorageObservation {
                        assertTrue(observations.isEmpty(), "Unexpected or excess checkpoint observation")
                        observations += it
                    },
            )
        val result = service.run(request)
        assertEquals(1, describes, "Unconsumed exact-description expectation")
        assertEquals(1, observations.size, "Unconsumed checkpoint expectation")
        assertSame(original.lease, request.lease)
        assertSame(plan, request.plan)
        assertEquals(original.budget.resources, request.budget.resources)
        assertEquals(original.budget.returnedBytes, request.budget.returnedBytes)
        val qualified = assertInstanceOf(QueryExecutionResult.Qualified::class.java, result)
        assertEquals(listOf(selector), qualified.result.symbolRows().map { it.selector })
        val continuation = qualified.continuation
        if (continuation is QueryContinuationState.Resumable) {
            assertSame(plan, continuation.checkpoint.plan)
            assertSame(original.lease, continuation.checkpoint.lease)
        }
        return qualified to observations.single()
    }

    private fun otherSelector(
        fixture: QueryServiceTest,
        first: SymbolSelector,
        name: String = "OtherService",
        offset: Int = 40,
    ): SymbolSelector {
        val basis = fixture.selection()
        val path = Path.of(first.file.stableValue)
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.CLASS,
                    name,
                    first.lease,
                    path,
                    path.toUri().toString(),
                    offset,
                )
                .refined()
        val selection =
            SymbolDiscoverySelection.restore(first.lease, first.scope, candidate, basis.constraints).refined()
        val signature = CanonicalCompilerSignature.classLike("sample.$name").refined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    first.file,
                    offset,
                    offset + 20,
                    name,
                    "sample.$name",
                    CompilerSymbolKind.CLASSLIKE,
                    signature,
                )
                .refined()
        return SymbolSelector.issue(selection, evidence).refined()
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
