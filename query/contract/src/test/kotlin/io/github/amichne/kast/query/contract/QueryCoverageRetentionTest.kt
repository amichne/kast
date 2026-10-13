package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCoverageRetentionTest {
    @Test
    fun `complete coverage keeps its conservative count charge`() {
        val expected = listOf(0 to 123L, 9 to 123L, 10 to 126L, 99 to 126L, 100 to 129L, Int.MAX_VALUE to 150L)
        for ((count, bytes) in expected) {
            val coverage = QueryCoverage.Complete(QueryCount.parse(count).refined())
            assertEquals(bytes, coverage.retainedCoverageBytes())
        }
    }

    @Test
    fun `qualified coverage charge ignores process dependent hash width`() {
        val shortHash = coverageForHash(0)
        val fullHash = coverageForHash(Int.MIN_VALUE)
        assertTrue(shortHash.hashCode() in 0..MAXIMUM_FIXTURE_HASH_OFFSET)
        assertTrue(fullHash.hashCode() in Int.MIN_VALUE..Int.MIN_VALUE + MAXIMUM_FIXTURE_HASH_OFFSET)
        val shortSnapshot = snapshot(shortHash)
        val fullSnapshot = snapshot(fullHash)
        assertEquals(shortSnapshot.coverage, shortHash)
        assertEquals(fullSnapshot.coverage, fullHash)
        assertEquals(
            fullSnapshot.retainedBytes,
            shortSnapshot.retainedBytes,
            "Qualified coverage stores the same fixed-width count and finite limitation fields",
        )
    }

    private fun coverageForHash(firstHash: Int): QueryCoverage.Qualified {
        val limitation = QueryLimitation.RESULT_LIMIT_REACHED
        val listHash = listOf(limitation).hashCode()
        // Choose admitted counts that expose Object.toString width. The expected charge does not use this hash.
        val count =
            (0..MAXIMUM_FIXTURE_HASH_OFFSET)
                .map { offset -> HASH_MULTIPLIER_INVERSE * (firstHash + offset - listHash) }
                .first { it >= 0 }
        return QueryCoverage.Qualified.create(QueryCount.parse(count).refined(), setOf(limitation)).refined()
    }

    private fun snapshot(coverage: QueryCoverage.Qualified): QueryRetainedResult {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refined())
        val execution =
            QueryExecutionResult.Qualified(
                QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                coverage,
                QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE),
            )
        return QueryRetainedResult.capture(lease, execution).refined()
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Coverage fixture rejected: $failure")
        }

    private companion object {
        const val HASH_MULTIPLIER_INVERSE = -1_108_378_657
        const val MAXIMUM_FIXTURE_HASH_OFFSET = 31
    }
}
