package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedReadReplayTest {
    @Test
    fun `moved read is discarded and fresh evaluation is published`() = runTest {
        var calls = 0
        var pauses = 0
        var retries = 0
        val progress = HostedQueryProgress()
        val observation =
            object : IntellijReadObservation {
                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
                    assertEquals(IntellijReadCounter.EPOCH_MOVED_RETRIES, counter)
                    retries += amount
                }

                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
            }
        val result =
            retryMovedHostedRead(
                HostedReadReplayPolicy.RETRY_MOVED_READ,
                observation,
                onRetry = progress::restartAfterMovedRead,
                pause = { pauses++ },
            ) {
                progress.advance(HostedQueryStage.PROJECT_ADMISSION)
                progress.advance(HostedQueryStage.SEMANTIC_READ)
                progress.advance(HostedQueryStage.CONTENT_REVALIDATION)
                if (++calls == 1)
                    HostedSemanticRead.Rejected(HostedQueryFailure.Freshness(VfsPassiveReadAdmissionFailure.Moved))
                else {
                    progress.advance(HostedQueryStage.RESULT_DETACHED)
                    HostedSemanticRead.Resolved(42)
                }
            }
        assertEquals(HostedSemanticRead.Resolved(42), result)
        assertEquals(2, calls)
        assertEquals(1, pauses)
        assertEquals(1, retries)
        assertEquals(HostedQueryStage.RESULT_DETACHED, progress.stage)
    }

    @Test
    fun `single evaluation and nonmoving failure are not repeated`() = runTest {
        val moved = HostedSemanticRead.Rejected(HostedQueryFailure.Freshness(VfsPassiveReadAdmissionFailure.Moved))
        val result =
            retryMovedHostedRead(
                HostedReadReplayPolicy.SINGLE_EVALUATION,
                IntellijReadObservation.None,
                pause = {
                    error("must not pause")
                },
            ) {
                moved
            }
        assertEquals(moved, result)
        assertEquals(
            HostedSemanticRead.Rejected(HostedQueryFailure.CONTENT_MOVED),
            retryMovedHostedRead(
                HostedReadReplayPolicy.RETRY_MOVED_READ,
                IntellijReadObservation.None,
                pause = {
                    error("must not pause")
                },
            ) {
                HostedSemanticRead.Rejected(HostedQueryFailure.CONTENT_MOVED)
            },
        )
    }

    @Test
    fun `persistent movement remains bounded by the enclosing host deadline`() = runTest {
        var calls = 0
        val failure = runCatching {
            withTimeout(50) {
                retryMovedHostedRead(HostedReadReplayPolicy.RETRY_MOVED_READ, IntellijReadObservation.None) {
                    calls++
                    HostedSemanticRead.Rejected(HostedQueryFailure.Freshness(VfsPassiveReadAdmissionFailure.Moved))
                }
            }
        }
            .exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(calls > 1)
    }
}
