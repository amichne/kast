package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HostedIndexingRetryTest {
    private val indexing =
        HostedSemanticReadResult.Rejected(
            HostedQueryFailure.ProjectAdmission(ExistingProjectAdmissionFailure.DumbMode),
            HostedQueryStage.PROJECT_ADMISSION,
        )

    @Test
    fun `a presemantic indexing race waits and retries exactly once`() = runTest {
        var reads = 0
        var waits = 0
        val result =
            retryPresemanticIndexing(
                read = { if (++reads == 1) indexing else HostedSemanticReadResult.Completed(42) },
                wait = {
                    waits++
                    IndexingWait.Ready
                },
            )
        assertEquals(HostedSemanticReadResult.Completed(42), result)
        assertEquals(2, reads)
        assertEquals(1, waits)
    }

    @Test
    fun `indexing wait exhaustion preserves the original finite rejection`() = runTest {
        var reads = 0
        val result =
            retryPresemanticIndexing(
                read = {
                    reads++
                    indexing
                },
                wait = { IndexingWait.Unavailable },
            )
        assertSame(indexing, result)
        assertEquals(1, reads)
    }

    @Test
    fun `a rejection after semantic work does not replay the read`() = runTest {
        val late = HostedSemanticReadResult.Rejected(HostedQueryFailure.INDEXING, HostedQueryStage.SEMANTIC_READ)
        var waits = 0
        val result =
            retryPresemanticIndexing(
                read = { late },
                wait = {
                    waits++
                    IndexingWait.Ready
                },
            )
        assertSame(late, result)
        assertEquals(0, waits)
    }
}
