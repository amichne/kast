package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.query.protocol.QueryCheckpointRestoration
import io.github.amichne.kast.query.protocol.QueryResultRestoration
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPublicationFailureCause
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

internal class HostedAutomaticPublicationSessionTest {
    @Test
    fun `actual hosted session commits final rows and progress only after fitted rejection admission`() = runTest {
        val case = HostedAutomaticPublicationCase()
        val page = case.run()
        val token = case.token(page)
        val evidence = case.rejection(page).evidence as QueryCompletionEvidenceDocument.Retained
        assertEquals(QueryCheckpointRestoration.Unavailable, case.restore(token))
        assertEquals(
            QueryResultRestoration.Unavailable,
            case.store.restoreResult(evidence.result, case.hosted.context.authority),
        )
        case.fit(page)
        assertEquals(QueryCheckpointRestoration.Unavailable, case.restore(token))
        assertEquals(Refinement.Refined(Unit), case.hosted.commitRejected())
        assertInstanceOf(QueryCheckpointRestoration.Restored::class.java, case.restore(token))
        val read = assertInstanceOf(OperationOutcome.Qualified::class.java, case.read(evidence.result))
        assertEquals(
            1,
            (read.evidence.payload as io.github.amichne.kast.protocol.contract.QueryRunResult).items.values.size,
        )
        assertEquals(1, case.executions)
    }

    @Test
    fun `actual hosted prepared discard releases final rows and progress without making them public`() = runTest {
        val case = HostedAutomaticPublicationCase()
        val page = case.run()
        val token = case.token(page)
        val result = (case.rejection(page).evidence as QueryCompletionEvidenceDocument.Retained).result
        case.hosted.discard()
        case.hosted.discard()
        assertEquals(QueryCheckpointRestoration.Unavailable, case.restore(token))
        assertEquals(
            QueryResultRestoration.Unavailable,
            case.store.restoreResult(result, case.hosted.context.authority),
        )
        assertEquals(0L, case.store.retentionMeasurements().retainedBytes.value)
        assertEquals(0, case.store.retentionMeasurements().retainedEntries.value)
        assertEquals(1, case.executions)
    }

    @Test
    fun `actual hosted unavailable evidence preserves its advertised final progress after fitting`() = runTest {
        val case = HostedAutomaticPublicationCase(retainedRows = false)
        val page = case.run()
        val token = case.token(page)
        assertInstanceOf(QueryCompletionEvidenceDocument.Unavailable::class.java, case.rejection(page).evidence)
        assertEquals(QueryCheckpointRestoration.Unavailable, case.restore(token))
        case.fit(page)
        assertEquals(Refinement.Refined(Unit), case.hosted.commitRejected())
        assertInstanceOf(QueryCheckpointRestoration.Restored::class.java, case.restore(token))
        assertEquals(1, case.store.retentionMeasurements().retainedEntries.value)
        assertEquals(1, case.executions)
    }

    @Test
    fun `actual hosted unavailable evidence cannot publish progress before encoder admission`() = runTest {
        val case = HostedAutomaticPublicationCase(retainedRows = false)
        val page = case.run()
        val token = case.token(page)
        assertEquals(Refinement.Refined(Unit), case.hosted.commitRejected())
        assertEquals(QueryCheckpointRestoration.Unavailable, case.restore(token))
        assertEquals(0L, case.store.retentionMeasurements().retainedBytes.value)
        assertEquals(0, case.store.retentionMeasurements().retainedEntries.value)
        assertEquals(1, case.executions)
    }

    @Test
    fun `another fitted rejected page cannot authorize this actual hosted checkpoint`() = runTest {
        val case = HostedAutomaticPublicationCase(retainedRows = false)
        val other = HostedAutomaticPublicationCase(retainedRows = false)
        val page = case.run()
        val token = case.token(page)
        case.fit(other.run())
        assertEquals(Refinement.Refined(Unit), case.hosted.commitRejected())
        assertEquals(QueryCheckpointRestoration.Unavailable, case.restore(token))
        assertEquals(0L, case.store.retentionMeasurements().retainedBytes.value)
        other.hosted.discard()
        assertEquals(0L, other.store.retentionMeasurements().retainedBytes.value)
        assertEquals(1, case.executions)
        assertEquals(1, other.executions)
    }

    @Test
    fun `actual hosted final commit rejects original checkpoint expiry and releases staged state`() = runTest {
        val case = HostedAutomaticPublicationCase()
        val page = case.run()
        val token = case.token(page)
        case.fit(page)
        case.now = 1_000_001L
        assertEquals(
            Refinement.Rejected(HostedQueryFailure.Publication(HostedPublicationFailureCause.EXPIRED)),
            case.hosted.commitRejected(),
        )
        assertEquals(QueryCheckpointRestoration.Unavailable, case.restore(token))
        assertEquals(0L, case.store.retentionMeasurements().retainedBytes.value)
        assertEquals(1, case.executions)
    }
}
