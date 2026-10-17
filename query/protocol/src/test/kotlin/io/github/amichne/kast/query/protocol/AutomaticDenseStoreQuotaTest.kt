package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QueryCheckpointStorageOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AutomaticDenseStoreQuotaTest : AutomaticDenseRetentionCase() {
    @Test
    fun `store quota completes dense references with unchanged checkpoint and execution grants`() = runTest {
        val store = QueryStateStore(clock = { 0L })
        val protocol = CanonicalQueryProtocol(service, fixture.references, store)
        val complete =
            assertInstanceOf(
                OperationOutcome.Complete::class.java,
                protocol.execute(
                    input,
                    fixture.authority,
                    grant,
                    policy(
                        rows = 20,
                        byteLimit = 32_768,
                        retainedBytes = ReadLimitParameter.QUERY_CONTINUATION_BYTES.defaultValue.toLong(),
                    ),
                ),
            )
        val payload = complete.evidence.payload as QueryRunResult
        assertEquals(QueryInvocationStop.COMPLETED, payload.invocation!!.stop)
        assertEquals(1_001, payload.invocation!!.accumulatedRowCount)
        val providerPositions = positions.toList()
        assertEquals((0L..1_000L step 20).toList(), providerPositions)
        assertTrue(checkpointAdmissions.isNotEmpty())
        assertTrue(checkpointAdmissions.all { it.outcome == QueryCheckpointStorageOutcome.WITHIN_LIMIT })
        assertTrue(checkpointAdmissions.all { it.allowance.value <= grant.checkpointBytes.value })

        val items = retainedRows(protocol, payload)
        val references = items.map { it as QueryResultItemDocument.ReferenceOccurrence }
        assertEquals(
            List(1_001) { (100 + it * 32) to (120 + it * 32) },
            references
                .map {
                    it.occurrence.occurrence.range.startInclusive.value to
                        it.occurrence.occurrence.range.endExclusive.value
                }
                .sortedBy { it.first },
        )
        assertEquals(1_001, items.map { it.rowId }.distinct().size)
        assertEquals(providerPositions, positions, "Retained output cannot repeat semantic work")
    }

    private suspend fun retainedRows(
        protocol: CanonicalQueryProtocol,
        payload: QueryRunResult,
    ): List<QueryResultItemDocument> {
        val reference = assertInstanceOf(QueryResultRetention.Retained::class.java, payload.retention).reference
        val items = payload.items.values.toMutableList()
        var cursor = payload.nextCursor
        repeat(1_001) {
            val next = cursor ?: return items
            val page =
                assertInstanceOf(
                    OperationOutcome.Complete::class.java,
                    protocol.executePage(
                        QueryRunRequest.ReadResult.occurrences(reference, next),
                        fixture.authority,
                        grant,
                    ),
                )
            val result = page.evidence.payload as QueryRunResult
            assertEquals(QueryResultInterpretationDocument.QueryResult, result.interpretation)
            items += result.items.values
            cursor = result.nextCursor
        }
        error("Finite retained output must drain")
    }
}
