package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedExecutionBudgetRequest
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryStage
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadDeadline
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadExecutionFixture
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedSemanticReadResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
    fun `caller timeout expires during indexing wait`() =
        assertCallerTimeout(
            waitMillis = 90L,
            secondReadMillis = 1L,
            succeeds = false,
            elapsedMillis = 100L,
            expectedReads = 1,
        )

    @Test
    fun `retry cannot replenish caller timeout`() =
        assertCallerTimeout(
            waitMillis = 30L,
            secondReadMillis = 60L,
            succeeds = false,
            elapsedMillis = 100L,
            expectedReads = 2,
        )

    @Test
    fun `retry can complete within remaining caller lifetime`() =
        assertCallerTimeout(
            waitMillis = 30L,
            secondReadMillis = 49L,
            succeeds = true,
            elapsedMillis = 99L,
            expectedReads = 2,
        )

    @Test
    fun `committed read and admitted grant survive terminal observation past the deadline`() = runTest {
        var now = 0L
        var commits = 0
        val budget =
            HostedExecutionBudgetRequest(
                RequestedExecutionBudget.RunnerControlled(
                    timeout = (ElapsedTimeLimitMillis.parse(100L) as Refinement.Refined).value
                )
            )
        val deadline = HostedReadDeadline(ReadLimits.Default, { now }, budget)
        val executor = HostedReadExecutionFixture(backgroundScope, { now }, afterDiagnostic = { now = 110_000_000L })
        try {
            val result =
                retryPresemanticIndexing(
                    read = {
                        executor.read(budget, deadline, onCommit = { commits++ }) { report ->
                            now = 90_000_000L
                            report
                        }
                    },
                    wait = { error("A finalized semantic read cannot wait or replay") },
                    deadline = deadline,
                )
            val completed = assertInstanceOf(HostedSemanticReadResult.Completed::class.java, result)
            val report = assertInstanceOf(ExecutionBudgetReport.RunnerControlled::class.java, completed.value)
            assertEquals(100L, report.timeout?.value)
            assertEquals(1, commits)
            assertEquals(110_000_000L, now)
        } finally {
            executor.close()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun assertCallerTimeout(
        waitMillis: Long,
        secondReadMillis: Long,
        succeeds: Boolean,
        elapsedMillis: Long,
        expectedReads: Int,
    ) = runTest {
        val started = testScheduler.currentTime
        val budget =
            HostedExecutionBudgetRequest(
                RequestedExecutionBudget.RunnerControlled(
                    timeout = (ElapsedTimeLimitMillis.parse(100L) as Refinement.Refined).value
                )
            )
        val deadline = HostedReadDeadline(ReadLimits.Default, { testScheduler.currentTime * 1_000_000L }, budget)
        val executor = HostedReadExecutionFixture(backgroundScope, { testScheduler.currentTime * 1_000_000L })
        var reads = 0
        try {
            val result =
                retryPresemanticIndexing(
                    read = {
                        if (++reads == 1) {
                            delay(20L)
                            indexing
                        } else {
                            executor.read(budget, deadline) {
                                delay(secondReadMillis)
                                42
                            }
                        }
                    },
                    wait = {
                        delay(waitMillis)
                        IndexingWait.Ready
                    },
                    deadline = deadline,
                )
            if (succeeds) assertEquals(HostedSemanticReadResult.Completed(42), result)
            else {
                val rejected = assertInstanceOf(HostedSemanticReadResult.Rejected::class.java, result)
                assertEquals(HostedQueryFailure.CALLER_TIMEOUT, rejected.failure)
                assertEquals(
                    if (expectedReads == 1) HostedQueryStage.PROJECT_ADMISSION else HostedQueryStage.SEMANTIC_READ,
                    rejected.stage,
                )
                assertEquals(expectedReads == 1, rejected.executionBudget == ExecutionBudgetPresence.Absent)
            }
            assertEquals(elapsedMillis, testScheduler.currentTime - started)
            assertEquals(expectedReads, reads)
        } finally {
            executor.close()
        }
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
                wait = { IndexingWait.TimedOut },
            )
        assertSame(indexing, result)
        assertEquals(1, reads)
    }

    @Test
    fun `project disposal while waiting preserves the original rejection`() = runTest {
        var reads = 0
        val result =
            retryPresemanticIndexing(
                read = {
                    reads++
                    indexing
                },
                wait = { IndexingWait.Disposed },
            )
        assertSame(indexing, result)
        assertEquals(1, reads)
    }

    @Test
    fun `a second indexing rejection cannot schedule another wait`() = runTest {
        var reads = 0
        var waits = 0
        val result =
            retryPresemanticIndexing(
                read = {
                    reads++
                    indexing
                },
                wait = {
                    waits++
                    IndexingWait.Ready
                },
            )
        assertSame(indexing, result)
        assertEquals(2, reads)
        assertEquals(1, waits)
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
