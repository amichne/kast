@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedReadPublicationEffectTest {
    @Test
    fun `publication follows drained operation and accepted host completion`() = runTest {
        val events = mutableListOf<String>()
        val executor = HostedQueryExecutor(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val effect = RecordingPublication(events)
        try {
            val request = async {
                executor.execute(executor.endpoint) { progress ->
                    assertEquals(Refinement.Refined(Unit), progress.publicationEffects.prepare(effect))
                    entered.complete(Unit)
                    finish.await()
                    events += "operation-ended"
                    7
                }
            }
            entered.await()
            assertEquals(emptyList<String>(), events)
            finish.complete(Unit)
            assertEquals(HostedExecution.Completed(7), request.await())
            assertEquals(listOf("operation-ended", "commit"), events)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `caller cancellation retains ownership through drainage then discards publication`() = runTest {
        val events = mutableListOf<String>()
        val executor = HostedQueryExecutor(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        try {
            val request = async {
                executor.execute(executor.endpoint) { progress ->
                    progress.publicationEffects.prepare(RecordingPublication(events))
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            drain.await()
                            events += "drained"
                        }
                    }
                }
            }
            entered.await()
            request.cancel()
            runCurrent()
            assertEquals(emptyList<String>(), events)
            assertFalse(request.isCompleted)
            drain.complete(Unit)
            request.join()
            assertEquals(listOf("drained", "discard"), events)
        } finally {
            drain.complete(Unit)
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `semantic rejection cannot commit prepared state`() = runTest {
        for (classification in
            listOf(
                HostedDiagnosticOutcome.Rejected(HostedQueryFailure.STALE_REQUEST),
                HostedDiagnosticOutcome.Evaluated(HostedEvaluationOutcome.REJECTED),
            )) {
            val events = mutableListOf<String>()
            val executor = HostedQueryExecutor(backgroundScope)
            try {
                executor.execute(executor.endpoint, outcome = { classification }) { progress ->
                    progress.publicationEffects.prepare(RecordingPublication(events))
                    7
                }
                assertEquals(listOf("discard"), events)
            } finally {
                executor.retire()
                executor.drain()
            }
        }
    }

    @Test
    fun `retired host rejects detached result and discards prepared ownership`() = runTest {
        val events = mutableListOf<String>()
        val executor = HostedQueryExecutor(backgroundScope)
        try {
            val result =
                executor.execute(executor.endpoint) { progress ->
                    progress.publicationEffects.prepare(RecordingPublication(events))
                    executor.retire()
                    7
                }
            assertInstanceOf(HostedExecution.Rejected::class.java, result)
            assertEquals(listOf("discard"), events)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    @Test
    fun `moved attempt discards old state before permitting a new publication`() {
        val events = mutableListOf<String>()
        val owner = HostedReadPublicationOwner()
        owner.prepare(RecordingPublication(events))
        owner.restart()
        assertEquals(listOf("discard"), events)
        assertEquals(Refinement.Refined(Unit), owner.prepare(RecordingPublication(events)))
        assertEquals(Refinement.Refined(Unit), owner.commit())
        owner.discard()
        assertEquals(listOf("discard", "commit"), events)
        assertEquals(Refinement.Rejected(HostedQueryFailure.STALE_REQUEST), owner.commit())
    }

    @Test
    fun `failed store commit rejects result and revokes prepared resources`() = runTest {
        val events = mutableListOf<String>()
        val executor = HostedQueryExecutor(backgroundScope)
        try {
            val result =
                executor.execute(executor.endpoint) { progress ->
                    progress.publicationEffects.prepare(
                        RecordingPublication(
                            events,
                            Refinement.Rejected(HostedQueryFailure.STALE_REQUEST),
                        )
                    )
                    7
                }
            assertEquals(HostedExecution.Rejected(HostedQueryFailure.STALE_REQUEST), result)
            assertEquals(listOf("commit", "discard"), events)
        } finally {
            executor.retire()
            executor.drain()
        }
    }

    private class RecordingPublication(
        private val events: MutableList<String>,
        private val result: Refinement<Unit, HostedQueryFailure> = Refinement.Refined(Unit),
    ) : HostedReadPublicationEffect {
        override fun commit(): Refinement<Unit, HostedQueryFailure> {
            events += "commit"
            return result
        }

        override fun discard() {
            events += "discard"
        }
    }
}
