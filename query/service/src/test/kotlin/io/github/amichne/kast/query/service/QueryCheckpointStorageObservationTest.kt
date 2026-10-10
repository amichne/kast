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
    fun `empty discovery checkpoint keeps the existing 4608 byte accounting charge`() {
        val fixture = QueryServiceTest()
        val request = fixture.request(fixture.symbolPlan(), 8L)
        val checkpoint =
            PipelineCheckpoint(
                request.plan,
                request.lease,
                emptyList(),
                emptyMap(),
                QueryJoinSnapshot(emptyMap()),
                emptySet(),
                0.queryCount(),
            )
        val estimate = checkpoint.storageEstimate()
        assertEquals(4608L, checkpoint.retainedBytes)
        assertEquals(4608L, estimate.tasks.value)
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

    private fun otherSelector(fixture: QueryServiceTest, first: SymbolSelector): SymbolSelector {
        val basis = fixture.selection()
        val path = Path.of(first.file.stableValue)
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.CLASS,
                    "OtherService",
                    first.lease,
                    path,
                    path.toUri().toString(),
                    40,
                )
                .refined()
        val selection =
            SymbolDiscoverySelection.restore(first.lease, first.scope, candidate, basis.constraints).refined()
        val signature = CanonicalCompilerSignature.classLike("sample.OtherService").refined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    first.file,
                    40,
                    60,
                    "OtherService",
                    "sample.OtherService",
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
