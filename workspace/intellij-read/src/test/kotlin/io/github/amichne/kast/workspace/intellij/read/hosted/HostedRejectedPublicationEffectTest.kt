package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedRejectedPublicationEffectTest {
    @Test
    fun `fitted policy rejection commits retained evidence while preserving rejected diagnostics`() = runTest {
        val events = mutableListOf<String>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { 0L }) { limits ->
                HostedReadDiagnostics({ 0L }, limits, publish = receipts::add)
            }
        val capability = retainedEvidence()
        try {
            val result =
                executor.execute(
                    executor.endpoint,
                    outcome = { HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.REJECTED) },
                ) { progress ->
                    progress.publicationEffects.prepare(RecordingPublication(events, rejectedPublication = capability))
                    7
                }
            assertEquals(HostedExecution.Completed(7), result)
            assertEquals(listOf("commit"), events)
            assertEquals(HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.REJECTED), receipts.single().outcome)
            assertPolicyEvidenceCounts(receipts.single(), committed = 1, rejected = 0, discarded = 0)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `freshness rejection discards fitted retained policy evidence`() = runTest {
        val events = mutableListOf<String>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { 0L }) { limits ->
                HostedReadDiagnostics({ 0L }, limits, publish = receipts::add)
            }
        var validations = 0
        try {
            val result =
                executor.execute(executor.endpoint) { progress ->
                    runHostedReadTransaction(
                        progress,
                        validate = {
                            if (++validations == 1) Refinement.Refined(Unit)
                            else Refinement.Rejected(HostedQueryFailure.STALE_REQUEST)
                        },
                    ) {
                        progress.publicationEffects.prepare(
                            RecordingPublication(events, rejectedPublication = retainedEvidence())
                        )
                        7
                    }
                }
            assertInstanceOf(HostedExecution.Completed::class.java, result)
            assertEquals(
                HostedSemanticRead.Rejected(HostedQueryFailure.STALE_REQUEST),
                (result as HostedExecution.Completed).value,
            )
            assertEquals(2, validations)
            assertEquals(listOf("discard"), events)
            assertEquals(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.STALE_REQUEST), receipts.single().outcome)
            assertPolicyEvidenceCounts(receipts.single(), committed = 0, rejected = 0, discarded = 1)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `failed retained policy evidence commit rejects publication and revokes ownership`() = runTest {
        val events = mutableListOf<String>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { 0L }) { limits ->
                HostedReadDiagnostics({ 0L }, limits, publish = receipts::add)
            }
        val failure = HostedQueryFailure.Publication(HostedPublicationFailureCause.CLAIM_UNAVAILABLE)
        try {
            val result =
                executor.execute(
                    executor.endpoint,
                    outcome = { HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.REJECTED) },
                ) { progress ->
                    progress.publicationEffects.prepare(
                        RecordingPublication(events, Refinement.Rejected(failure), retainedEvidence())
                    )
                    7
                }
            assertEquals(HostedExecution.Rejected(failure), result)
            assertEquals(listOf("commit", "discard"), events)
            assertEquals(HostedDiagnosticOutcome.Rejected(failure), receipts.single().outcome)
            assertPolicyEvidenceCounts(receipts.single(), committed = 0, rejected = 1, discarded = 1)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `retired host discards fitted retained policy evidence`() = runTest {
        val events = mutableListOf<String>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { 0L }) { limits ->
                HostedReadDiagnostics({ 0L }, limits, publish = receipts::add)
            }
        try {
            val result =
                executor.execute(
                    executor.endpoint,
                    outcome = { HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.REJECTED) },
                ) { progress ->
                    progress.publicationEffects.prepare(
                        RecordingPublication(events, rejectedPublication = retainedEvidence())
                    )
                    executor.retire()
                    7
                }
            assertInstanceOf(HostedExecution.Rejected::class.java, result)
            assertEquals(listOf("discard"), events)
            assertPolicyEvidenceCounts(receipts.single(), committed = 0, rejected = 0, discarded = 1)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `ended rejection publication fails without repeating effects`() {
        val events = mutableListOf<String>()
        val owner = HostedReadPublicationOwner()
        owner.prepare(RecordingPublication(events, rejectedPublication = retainedEvidence()))
        assertEquals(Refinement.Refined(Unit), owner.commitRetainedRejection())
        assertEquals(Refinement.Rejected(HostedQueryFailure.STALE_REQUEST), owner.commitRetainedRejection())
        assertEquals(listOf("commit"), events)
    }

    private class RecordingPublication(
        private val events: MutableList<String>,
        private val result: Refinement<Unit, HostedQueryFailure> = Refinement.Refined(Unit),
        override val rejectedPublication: HostedReadRejectedPublication = HostedReadRejectedPublication.Discard,
    ) : HostedReadPublicationEffect {
        override fun commit(): Refinement<Unit, HostedQueryFailure> {
            events += "commit"
            return result
        }

        override fun discard() {
            events += "discard"
        }
    }

    private fun retainedEvidence() =
        HostedReadRejectedPublication.RetainedEvidence(
            QueryCompletionEvidenceDocument.Retained(
                (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined)
                    .value,
                (io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(
                        emptyList<io.github.amichne.kast.protocol.contract.QueryResultItemDocument>()
                    ) as Refinement.Refined)
                    .value,
                io.github.amichne.kast.protocol.contract.QueryQuestionDocument(
                    io.github.amichne.kast.protocol.contract.QueryFromDocument.Location(
                        (io.github.amichne.kast.protocol.contract.ProtocolText.parse("Example.kt")
                                as Refinement.Refined)
                            .value,
                        (io.github.amichne.kast.protocol.contract.ProtocolOffset.parse(0) as Refinement.Refined).value,
                    ),
                    (io.github.amichne.kast.protocol.contract.BoundedProtocolList.create(
                            emptyList<io.github.amichne.kast.protocol.contract.QueryStepDocument>()
                        ) as Refinement.Refined)
                        .value,
                    io.github.amichne.kast.protocol.contract.QueryOutputDocument.Occurrences,
                    io.github.amichne.kast.protocol.contract.QueryCompletionDocument(),
                ),
            )
        )

    private fun assertPolicyEvidenceCounts(
        receipt: HostedReadDiagnosticReceipt,
        committed: Long,
        rejected: Long,
        discarded: Long,
    ) {
        val counts =
            receipt.counters.filter {
                it.counter in
                    setOf(
                        IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_COMMITTED,
                        IntellijReadCounter.QUERY_POLICY_EVIDENCE_COMMIT_REJECTIONS,
                        IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_DISCARDED,
                    )
            }
        assertEquals(3, counts.size)
        assertTrue(counts.all { it.contributor == IntellijReadContributor.NONE })
        assertEquals(
            mapOf(
                IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_COMMITTED to committed,
                IntellijReadCounter.QUERY_POLICY_EVIDENCE_COMMIT_REJECTIONS to rejected,
                IntellijReadCounter.QUERY_POLICY_EVIDENCE_PUBLICATIONS_DISCARDED to discarded,
            ),
            counts.associate { it.counter to it.count },
        )
    }
}
