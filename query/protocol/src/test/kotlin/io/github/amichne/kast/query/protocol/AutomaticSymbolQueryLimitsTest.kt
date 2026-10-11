package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

internal class AutomaticSymbolQueryLimitsTest : AutomaticSymbolQueryCase() {
    @Test
    fun `aggregate work stop rejects completion while preserving proven rows and internal continuation`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val limited = budget.copy(resources = budget.resources.copy(workUnitLimit = WorkUnitLimit.parse(8).refined()))
        val rejection = completionRejection(protocol.execute(request, fixture.authority, limited, policy()))
        assertEquals(1, script.calls)
        assertEquals(1, retainedEvidence(rejection).preview.values.size)
        assertEquals(QueryInvocationStop.WORK_LIMIT, rejection.stop)
        val progress = originalCoverage(rejection).progress as QueryQualifiedProgressDocument.Resumable
        val rejectedResume =
            protocol.execute(QueryRunRequest.Resume(progress.continuationToken!!), fixture.authority, budget, policy())
                as OperationOutcome.Rejected
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryRunRejection.ExecutionRejected(
                io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.REQUEST_REJECTED
            ),
            rejectedResume.reason,
        )
        assertEquals(1, script.calls)
        val read =
            protocol.executePage(retainedEvidence(rejection).readRequest(), fixture.authority, budget)
                as OperationOutcome.Qualified
        assertEquals(1, read.evidence.payload.items.values.size)
        assertNull(read.qualification.progress.continuationToken)
        val resumed =
            protocol.executePage(QueryRunRequest.Resume(progress.continuationToken!!), fixture.authority, budget)
        assertInstanceOf(OperationOutcome.Complete::class.java, resumed)
        script.assertDrained()
    }

    @Test
    fun `cooperative cancellation rejects completion and preserves historical continuation`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)))
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejection =
            completionRejection(
                protocol.execute(request, fixture.authority, budget, policy(cancelled = { script.calls == 1 }))
            )
        assertEquals(1, script.calls)
        assertEquals(1, retainedEvidence(rejection).preview.values.size)
        assertEquals(QueryInvocationStop.CANCELLED, rejection.stop)
        val progress = originalCoverage(rejection).progress as QueryQualifiedProgressDocument.Resumable
        assertNotNull(progress.continuationToken)
        val read =
            protocol.executePage(retainedEvidence(rejection).readRequest(), fixture.authority, budget)
                as OperationOutcome.Qualified
        assertEquals(1, read.evidence.payload.items.values.size)
        assertEquals(1, script.calls)
        protocol.executePage(QueryRunRequest.Resume(progress.continuationToken!!), fixture.authority, budget)
        script.assertDrained()
    }

    @Test
    fun `elapsed allowance rejects completion without renewing public execution`() = runTest {
        var now = 0L
        val script = Script(listOf(listOf(row), listOf(row)), afterPage = { now = 1_000_000_000L })
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejection =
            completionRejection(protocol.execute(request, fixture.authority, budget, policy(nanoTime = { now })))
        assertEquals(1, script.calls)
        assertEquals(QueryInvocationStop.TIME_LIMIT, rejection.stop)
        val progress = originalCoverage(rejection).progress as QueryQualifiedProgressDocument.Resumable
        assertNotNull(progress.continuationToken)
        val rejectedResume =
            protocol.execute(QueryRunRequest.Resume(progress.continuationToken!!), fixture.authority, budget, policy())
                as OperationOutcome.Rejected
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryRunRejection.ExecutionRejected(
                io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument.REQUEST_REJECTED
            ),
            rejectedResume.reason,
        )
        assertEquals(1, script.calls)
        protocol.executePage(QueryRunRequest.Resume(progress.continuationToken!!), fixture.authority, budget)
        script.assertDrained()
    }

    @Test
    fun `semantic pages receive the remaining invocation time without an unresumable slice`() = runTest {
        var now = 0L
        val script = Script(listOf(listOf(row), listOf(row)), afterPage = { now = 900_000_000L })
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result = protocol.execute(request, fixture.authority, budget, policy(nanoTime = { now }))
        assertInstanceOf(OperationOutcome.Complete::class.java, result)
        assertEquals(listOf(1000L, 100L), script.timeGrants)
        script.assertDrained()
    }

    @Test
    fun `aggregate retained bytes stop without skipping a consumed page through a continuation`() = runTest {
        val script = Script(listOf(listOf(row), List(100) { row }))
        val observations = mutableListOf<QueryInvocationRetentionAdmission>()
        val base = policy(retainedBytes = 50_000)
        val observedPolicy = QueryInvocationPolicy(
            base.previewRows,
            base.previewBytesLimit,
            base.retainedBytes,
            base.previewBytes,
            nanoTime = base.nanoTime,
            retentionObservation = QueryInvocationRetentionObservation(observations::add),
        )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.execute(
                request,
                fixture.authority,
                budget.copy(resources = budget.resources.copy(resultLimit = ResultLimit.parse(100).refined())),
                observedPolicy,
            )
        val rejection = completionRejection(result)
        assertEquals(QueryInvocationStop.RETAINED_BYTES_LIMIT, rejection.stop)
        assertEquals(QueryInvocationRetentionStage.FACTS_REJECTED, observations.last().stage)
        assertEquals(QueryInvocationRetentionStage.BEFORE_FACTS, observations[observations.lastIndex - 1].stage)
        assertEquals(observations[observations.lastIndex - 1].facts, observations.last().facts)
        assertEquals(observations[observations.lastIndex - 1].total, observations.last().total)
        assertEquals(1, originalCoverage(rejection).knownMinimum.value)
        assertEquals(1, retainedEvidence(rejection).preview.values.size)
        assertNull(originalCoverage(rejection).progress.continuationToken)
        script.assertDrained()
    }

    @Test
    fun `hard cancellation never blindly replays semantic work`() = runTest {
        var calls = 0
        val operations = QueryOperations {
            calls++
            throw kotlinx.coroutines.CancellationException("scripted hard timeout")
        }
        val protocol = CanonicalQueryProtocol(operations, fixture.references)
        try {
            protocol.execute(request, fixture.authority, budget, policy())
            fail<Unit>("Expected hard cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `budget increase next action requires a decision and preserves the issued checkpoint`() = runTest {
        val script = Script(listOf(listOf(row), listOf(row)))
        val store = QueryStateStore()
        var receipt: QueryExecutionResult? = null
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { script.operations.run(it).also { result -> receipt = result } },
                fixture.references,
                store,
            )
        var calls = 0
        val accumulated =
            AutomaticSymbolQueryRunner(store, fixture.authority, policy()) { action, remaining ->
                    calls++
                    val original =
                        protocol.executePage(action, fixture.authority, remaining) as OperationOutcome.Qualified
                    val progress = original.qualification.progress as QueryQualifiedProgressDocument.Resumable
                    QueryInvocationPage(
                        OperationOutcome.Qualified(
                            original.evidence,
                            QueryRunQualification.create(
                                    original.qualification.knownMinimum,
                                    original.qualification.limitations,
                                    progress.copy(nextAction = ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET),
                                )
                                .refined(),
                        ),
                        receipt,
                    )
                }
                .run(request, budget)
                .refined()
        assertEquals(1, calls)
        assertEquals(QueryInvocationStop.BUDGET_INCREASE_REQUIRED, accumulated.stop)
        val rejection = completionRejection(present(store, accumulated))
        assertEquals(
            ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
            (originalCoverage(rejection).progress as QueryQualifiedProgressDocument.Resumable).nextAction,
        )

        assertInstanceOf(
            QueryContinuationState.Resumable::class.java,
            (accumulated.execution as QueryExecutionResult.Qualified).continuation,
        )
        val next =
            (accumulated.execution as QueryExecutionResult.Qualified).continuation as QueryContinuationState.Resumable
        val issued = store.retainedCheckpoint(request, next.checkpoint) as QueryCheckpointIssuance.Issued
        protocol.executePage(QueryRunRequest.Resume(issued.token), fixture.authority, budget)
        script.assertDrained()
    }

    private suspend fun present(store: QueryStateStore, accumulated: AccumulatedSymbolQuery): QueryPublishedPage {
        val claim = (store.acquireInitial(fixture.authority) as QueryInitialAcquisition.Acquired).claim
        return QueryPagePublication(store, QueryExecutionPublication.Immediate).execute(claim) {
            QueryInvocationProjection(fixture.references, store, QueryResultRetentionObservation.None)
                .project(request, fixture.authority, accumulated, policy(), claim)
        }
    }
}
