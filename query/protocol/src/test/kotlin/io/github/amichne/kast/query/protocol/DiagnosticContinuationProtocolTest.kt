package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationStop
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanCheckpoint
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanInventory
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanPage
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanStop
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticInventoryDocument
import io.github.amichne.kast.protocol.contract.DiagnosticProgressStop
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DiagnosticContinuationProtocolTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
    private val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refined())
    private val query = DiagnosticScopeQuery.parse(lease, "src").refined()
    private val budget =
        ResourceBudget(
            ResultLimit.parse(1).refined(),
            WorkUnitLimit.parse(10).refined(),
            ElapsedTimeLimitMillis.parse(1000).refined(),
        )
    private val request = DiagnosticCheckRequest(ProtocolText.parse("src").refined(), ProtocolCount.parse(1).refined())
    private val emptyPage =
        DiagnosticScanPage(emptyList(), emptyList(), emptySet(), DiagnosticScanInventory.Enumerating)
    private val checkpoint =
        object : DiagnosticScanCheckpoint {
            override val query = this@DiagnosticContinuationProtocolTest.query
            override val retainedBytes = 100L
        }

    @Test
    fun `enumeration page retains unknown total and replay does not reexecute`() = runTest {
        var scans = 0
        val protocol = protocol {
            scans++
            DiagnosticScanResult.Advancing(
                emptyPage,
                checkpoint,
                DiagnosticScanStop.Enumeration(DiagnosticEnumerationStop.FILE_LIMIT),
            )
        }
        val first = protocol.execute(request, lease, budget)
        val page = assertInstanceOf(OperationOutcome.Qualified::class.java, first)
        assertEquals(first, protocol.execute(request, lease, budget))
        assertEquals(1, scans)
        val typed = first as OperationOutcome.Qualified
        assertEquals(DiagnosticInventoryDocument.Enumerating, typed.evidence.payload.progress?.inventory)
        assertEquals(DiagnosticProgressStop.ENUMERATION_FILE_LIMIT, typed.evidence.payload.progress?.stop)
        assertNotNull(typed.qualification.continuation)
        assertEquals(0, typed.qualification.knownDiagnosticCount.value)
        assertNotNull(page)
    }

    @Test
    fun `resume preserves checkpoint and replay remains immutable`() = runTest {
        val file =
            DiagnosticScope.fromCanonicalPaths(lease, listOf(Path.of("/workspace/src/A.kt"))).refined().files.single()
        var scans = 0
        val protocol = protocol { admitted ->
            scans++
            when (admitted) {
                is DiagnosticScanRequest.First ->
                    DiagnosticScanResult.Advancing(
                        emptyPage,
                        checkpoint,
                        DiagnosticScanStop.Enumeration(DiagnosticEnumerationStop.FILE_LIMIT),
                    )
                is DiagnosticScanRequest.Resume -> {
                    assertSame(checkpoint, admitted.checkpoint)
                    DiagnosticScanResult.Complete(
                        DiagnosticScanPage(
                            emptyList(),
                            listOf(file),
                            emptySet(),
                            DiagnosticScanInventory.Exhausted(listOf(file)),
                        )
                    )
                }
            }
        }
        val first = protocol.execute(request, lease, budget) as OperationOutcome.Qualified
        val resumedRequest = request.copy(continuation = first.qualification.continuation)
        val largerGrant =
            budget.copy(
                workUnitLimit = WorkUnitLimit.parse(100).refined(),
                resultLimit = ResultLimit.parse(10).refined(),
            )
        val last = protocol.execute(resumedRequest, lease, largerGrant)
        assertInstanceOf(OperationOutcome.Complete::class.java, last)
        assertEquals(last, protocol.execute(resumedRequest, lease, largerGrant))
        assertEquals(first, protocol.execute(request, lease, budget))
        assertEquals(2, scans)
    }

    @Test
    fun `mismatch and stale basis reject without additional scan`() = runTest {
        var scans = 0
        val protocol = protocol {
            scans++
            DiagnosticScanResult.Advancing(emptyPage, checkpoint, DiagnosticScanStop.AnalysisPending)
        }
        val first = protocol.execute(request, lease, budget) as OperationOutcome.Qualified
        val resumed = request.copy(continuation = first.qualification.continuation)
        assertEquals(
            OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_REQUEST_MISMATCH),
            protocol.execute(resumed.copy(path = ProtocolText.parse("other").refined()), lease, budget),
        )
        assertEquals(
            OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_REQUEST_MISMATCH),
            protocol.execute(resumed.copy(limit = ProtocolCount.parse(2).refined()), lease, budget),
        )
        val moved = SemanticReadLease(root, EvidenceGeneration.parse(8).refined())
        assertEquals(
            OperationOutcome.Rejected(DiagnosticCheckRejection.STALE_CONTINUATION),
            protocol.execute(resumed, moved, budget),
        )
        assertEquals(1, scans)
    }

    @Test
    fun `expired and disposed checkpoints fail finitely`() = runTest {
        var now = 0L
        val store = DiagnosticCheckpointStore(ttlMillis = 1, clock = { now })
        val protocol =
            protocol(store) {
                DiagnosticScanResult.Advancing(emptyPage, checkpoint, DiagnosticScanStop.AnalysisPending)
            }
        val first = protocol.execute(request, lease, budget) as OperationOutcome.Qualified
        now = 1_000_000
        val resumed = request.copy(continuation = first.qualification.continuation)
        assertEquals(
            OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE),
            protocol.execute(resumed, lease, budget),
        )
        store.retire()
        assertEquals(
            OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE),
            protocol.execute(request, lease, budget),
        )
    }

    @Test
    fun `capacity is charged before token publication`() = runTest {
        val protocol =
            protocol(DiagnosticCheckpointStore(maximumBytes = 10)) {
                DiagnosticScanResult.Advancing(emptyPage, checkpoint, DiagnosticScanStop.AnalysisPending)
            }
        assertEquals(
            OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED),
            protocol.execute(request, lease, budget),
        )
    }

    @Test
    fun `long request identity is charged against checkpoint cap before publication`() = runTest {
        val path = "src/" + "a".repeat(10000)
        val longQuery = DiagnosticScopeQuery.parse(lease, path).refined()
        val longCheckpoint =
            object : DiagnosticScanCheckpoint {
                override val query = longQuery
                override val retainedBytes = 1L
            }
        val protocol =
            protocol(DiagnosticCheckpointStore(maximumCheckpointBytes = 10000)) {
                DiagnosticScanResult.Advancing(emptyPage, longCheckpoint, DiagnosticScanStop.AnalysisPending)
            }
        val result = protocol.execute(request.copy(path = ProtocolText.parse(path).refined()), lease, budget)
        assertEquals(OperationOutcome.Rejected(DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED), result)
    }

    private fun protocol(
        store: DiagnosticCheckpointStore = DiagnosticCheckpointStore(),
        scan: suspend (DiagnosticScanRequest) -> DiagnosticScanResult,
    ) =
        CanonicalDiagnosticCheckProtocol(
            DiagnosticScanOperations { request, _ -> scan(request) },
            CanonicalQueryReferences(),
            store,
        )

    private fun <T> Refinement<T, *>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
