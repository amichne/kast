package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingStatusDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal suspend fun assertCoverageResumed(
    fixture: NativePacketQueryCase,
    scenario: String,
    expected: ImpactValueSiteReferenceDocument,
    checkpoint: NativePacketQueryCheckpoint,
) {
    val source = fixture.source
    val producer = source.producer("", "${scenario}Case", "origin")
    val before = fixture.services.nativeFlowReads
    val uninterrupted =
        NativePacketQueryCapture(fixture.services)
            .capture(producer = producer, targets = emptyList(), outgoing = false, declarations = emptyList())
    val uninterruptedReads = fixture.services.nativeFlowReads - before
    val capture = NativePacketQueryCapture(fixture.services, checkpoint)
    val resumed =
        capture.capture(producer = producer, targets = emptyList(), outgoing = false, declarations = emptyList())
    assertEquals(
        "Resume repeated native work",
        uninterruptedReads,
        fixture.services.nativeFlowReads - before - uninterruptedReads,
    )
    checkpoint.assertResumed()
    for (result in listOf(uninterrupted, resumed)) {
        assertTrue("$scenario did not complete", result.outcome is OperationOutcome.Complete)
        val transfers =
            result.paths.flatMap { path ->
                path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
            }
        assertCoverageEndpoints(source = source, scenario = scenario, expected = expected, transfers = transfers)
        val accounting = result.retained.impactAccounting
        assertTrue(accounting is ImpactAccountingDocument.Investigated)
        accounting as ImpactAccountingDocument.Investigated
        assertEquals(ImpactAccountingStatusDocument.Conserved, accounting.status)
        assertEquals(0L, accounting.originalReadRejectionCount.value)
        assertTrue(result.retained.failures.values.isEmpty())
        assertTrue(result.retained.omissions.values.isEmpty())
        val complete = result.outcome as OperationOutcome.Complete
        assertEquals(QueryInvocationStop.COMPLETED, complete.evidence.payload.invocation?.stop)
    }
    assertEquals(uninterrupted.paths.size, resumed.paths.size)
    uninterrupted.paths.zip(resumed.paths).forEach { (original, restored) -> assertResumedPath(original, restored) }
    assertEquals(uninterrupted.retained.impactAccounting, resumed.retained.impactAccounting)
    assertEquals(uninterrupted.retained.interpretation, resumed.retained.interpretation)
    assertEquals(uninterrupted.retained.question, resumed.retained.question)

    val reads = fixture.services.nativeFlowReads
    assertRetentionAfterRejectedContinuations(capture, resumed)
    assertEquals(reads, fixture.services.nativeFlowReads)
    println(
        "COMPILER_COVERAGE_RESUMED scenario=$scenario paths=${resumed.paths.size} " +
            "nativeReadsPerRun=$uninterruptedReads retainedReadDelta=0"
    )
}

private suspend fun assertRetentionAfterRejectedContinuations(
    capture: NativePacketQueryCapture,
    resumed: NativePacketQueryResult,
) {
    // Pipeline resume is private to the automatic runner, not an externally admitted semantic restart.
    val token =
        QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000001").packetQueryRefined()
    assertEquals(
        OperationOutcome.Rejected(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REQUEST_REJECTED)
        ),
        capture.execute(QueryRunRequest.Resume(token)),
    )
    val unavailable =
        QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000001")
            .packetQueryRefined()
    assertEquals(
        OperationOutcome.Rejected(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE)
        ),
        capture.execute(QueryRunRequest.Resume(unavailable)),
    )
    val outcome = resumed.outcome as OperationOutcome.Complete
    val retention = outcome.evidence.payload.retention as QueryResultRetention.Retained
    val replay = capture.execute(QueryRunRequest.ReadResult.valuePaths(retention.reference))
    assertTrue(replay is OperationOutcome.Complete)
    assertEquals(resumed.retained, (replay as OperationOutcome.Complete).evidence.payload)
}

private fun assertResumedPath(original: ImpactPathDocument, restored: ImpactPathDocument) {
    // Exact steps include conditions, local versions, call frames, invocations and transfer qualifications.
    assertEquals(original.producer, restored.producer)
    assertEquals(original.steps, restored.steps)
    assertEquals(original.representation, restored.representation)
    assertTrue(original.terminal is ImpactPathTerminalDocument.SupportedDomainEnd)
    assertTrue(restored.terminal is ImpactPathTerminalDocument.SupportedDomainEnd)
    val before = (original.terminal as ImpactPathTerminalDocument.SupportedDomainEnd).observation
    val after = (restored.terminal as ImpactPathTerminalDocument.SupportedDomainEnd).observation
    // Remaining grants depend on elapsed time and invocation bookkeeping, not semantic identity.
    // Compare every terminal field except that allowance, and check both actual receipts against their grants.
    assertEquals(before.copy(domain = before.domain.copy(budget = after.domain.budget)), after)
    for (observation in listOf(before, after)) {
        val grant = observation.domain.budget
        assertTrue(grant is ImpactFlowBudgetDocument.Bounded)
        grant as ImpactFlowBudgetDocument.Bounded
        assertTrue(observation.examinedWorkUnits.value <= grant.maxWorkUnits.value)
        assertTrue(observation.retainedBytes.value <= grant.maxReturnedBytes.value)
    }
}

internal suspend fun assertCoverageEndpoints(
    source: NativePacketQuerySource,
    scenario: String,
    expected: ImpactValueSiteReferenceDocument,
    transfers: List<ImpactCompilerTransferDocument>,
) {
    val consumers =
        setOf(
            source.reference(source.declaration("", "consume")),
            source.reference(source.declaration("", "forbidden")),
        )
    val endpoints =
        transfers
            .map { it.target }
            .filter { site ->
                val role = site.role
                role is ImpactValueRoleDocument.Argument && role.invocation.callable in consumers
            }
            .toSet()
    println("COMPILER_COVERAGE_ACTUAL $scenario ${Json.encodeToString(endpoints.toList())}")
    assertEquals("Unexpected consumer endpoint set for $scenario", setOf(expected), endpoints)
    assertTrue(
        "Unrelated value reached a forbidden source range",
        transfers.none { it.target.range == source.markedRange("$scenario-forbidden") },
    )
}
