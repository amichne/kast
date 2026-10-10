@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedReadTraceIdentityTest {
    @Test
    fun `allocated identity precedes computation and matches native entry and terminal receipt`() = runTest {
        val identities = mutableListOf<HostedReadTraceIdentity>()
        val entries = mutableListOf<HostedNativePhaseEntry>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { 0L }) { limits ->
                HostedReadDiagnostics({ 0L }, limits, publishPhase = entries::add, publish = receipts::add)
            }
        val result =
            executor.execute(executor.endpoint, observeReadIdentity = identities::add) { progress ->
                assertEquals(1, identities.size)
                assertTrue(receipts.isEmpty())
                progress.observation.phase(IntellijReadPhase.REFERENCE_INVENTORY)
                42
            }
        assertEquals(HostedExecution.Completed(42), result)
        val identity = identities.single().value
        assertEquals(identity.toString(), entries.first().readId)
        assertEquals(identity, receipts.single().readId)
        executor.retire()
        executor.drain()
    }

    @Test
    fun `retired admission still joins its exact rejected receipt without evaluating`() = runTest {
        val identities = mutableListOf<HostedReadTraceIdentity>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { 0L }) { limits ->
                HostedReadDiagnostics({ 0L }, limits, publish = receipts::add)
            }
        val endpoint = executor.endpoint
        executor.retire()
        assertEquals(
            HostedExecution.Rejected(HostedQueryFailure.RETIRED),
            executor.execute(endpoint, observeReadIdentity = identities::add) {
                error("Retired admission cannot evaluate")
            },
        )
        assertEquals(identities.single().value, receipts.single().readId)
        assertEquals(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.RETIRED), receipts.single().outcome)
        executor.drain()
    }

    @Test
    fun `caller cancellation retains the allocated identity through drained rejection`() = runTest {
        val identities = mutableListOf<HostedReadTraceIdentity>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { 0L }) { limits ->
                HostedReadDiagnostics({ 0L }, limits, publish = receipts::add)
            }
        val work = async {
            executor.execute(executor.endpoint, observeReadIdentity = identities::add) { awaitCancellation() }
        }
        runCurrent()
        assertEquals(1, identities.size)
        work.cancel()
        work.join()
        assertEquals(identities.single().value, receipts.single().readId)
        assertEquals(HostedDiagnosticOutcome.Rejected(HostedQueryFailure.CANCELLED), receipts.single().outcome)
        executor.retire()
        executor.drain()
    }

    @Test
    fun `overlapping allocations stay distinct when completion order reverses`() = runTest {
        val first = mutableListOf<HostedReadTraceIdentity>()
        val second = mutableListOf<HostedReadTraceIdentity>()
        val receipts = mutableListOf<HostedReadDiagnosticReceipt>()
        val executor =
            HostedQueryExecutor(backgroundScope, { 0L }) { limits ->
                HostedReadDiagnostics({ 0L }, limits, publish = receipts::add)
            }
        val release = CompletableDeferred<Unit>()
        val pending = async {
            executor.execute(executor.endpoint, observeReadIdentity = first::add) {
                release.await()
                7
            }
        }
        runCurrent()
        assertEquals(
            HostedExecution.Completed(9),
            executor.execute(executor.endpoint, observeReadIdentity = second::add) { 9 },
        )
        assertNotEquals(first.single().value, second.single().value)
        assertEquals(second.single().value, receipts.single().readId)
        release.complete(Unit)
        assertEquals(HostedExecution.Completed(7), pending.await())
        assertEquals(listOf(second.single().value, first.single().value), receipts.map { it.readId })
        executor.retire()
        executor.drain()
    }
}
