package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryRetentionProgressContractTest {
    @Test
    fun `retention unavailable carries its original coverage and requires the exact resource evidence`() {
        val minimum = (QueryKnownMinimum.parse(7) as Refinement.Refined).value
        val coverages =
            listOf(QueryPreparedCoverageDocument.Complete, QueryPreparedCoverageDocument.Resumable) +
                QueryTerminalReasonDocument.entries.map(QueryPreparedCoverageDocument::TerminalIncomplete)
        for (coverage in coverages) {
            val progress = QueryQualifiedProgressDocument.RetentionUnavailable(coverage)
            assertEquals(
                Refinement.Rejected(QueryRunQualificationFailure.MISSING_RETENTION_LIMITATION),
                QueryRunQualification.create(minimum, listOf(QueryLimitationDocument.BYTE_LIMIT_REACHED), progress),
            )
            val qualification =
                (QueryRunQualification.create(
                        minimum,
                        listOf(
                            QueryLimitationDocument.BYTE_LIMIT_REACHED,
                            QueryLimitationDocument.RETENTION_LIMIT_REACHED,
                        ),
                        progress,
                    ) as Refinement.Refined)
                    .value
            assertEquals(
                coverage,
                (qualification.progress as QueryQualifiedProgressDocument.RetentionUnavailable).upstream,
            )
            assertEquals(minimum, qualification.knownMinimum)
            assertEquals(null, qualification.progress.continuationToken)
            assertEquals(
                QueryTerminalReasonDocument.CHECKPOINT_CAPACITY_EXCEEDED,
                qualification.progress.terminalReason,
            )
        }
    }
}
