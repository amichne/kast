@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportedModelRestoreTest {
    private fun TestScope.operation(limitMillis: Long) =
        (NativeWorkspacePreparation.admit("open", limitMillis, { currentTime * NANOS_PER_MILLI }) as Refinement.Refined)
            .value

    @Test
    fun `reuse waits for the stored model that IntelliJ restores after project open`() = runTest {
        lateinit var restored: () -> Unit
        val waiter = async {
            awaitImportedModelRestore(operation(10_000), isDisposed = { false }) { restored = it }
        }
        runCurrent()
        advanceTimeBy(860)
        runCurrent()
        assertFalse(waiter.isCompleted, "An early reuse decision reads an empty store and forces a Gradle reload")
        restored()
        assertEquals(ImportedModelRestore.RESTORED, waiter.await())
    }

    @Test
    fun `a store restored before subscription does not delay reuse`() = runTest {
        val outcome = awaitImportedModelRestore(operation(1_000), isDisposed = { false }) { it() }
        assertEquals(ImportedModelRestore.RESTORED, outcome)
        assertEquals(0, currentTime)
    }

    @Test
    fun `a restore that never completes ends at the parent deadline`() = runTest {
        val outcome = awaitImportedModelRestore(operation(1_000), isDisposed = { false }) {}
        assertEquals(ImportedModelRestore.DEADLINE_EXCEEDED, outcome)
        assertEquals(1_000, currentTime)
    }

    @Test
    fun `project disposal ends the wait without a restored result`() = runTest {
        val outcome = awaitImportedModelRestore(operation(10_000), isDisposed = { currentTime >= 300 }) {}
        assertEquals(ImportedModelRestore.DISPOSED, outcome)
        assertTrue(currentTime < 10_000)
    }

    @Test
    fun `disposal wins over a restore signal from the disposed project`() = runTest {
        val outcome = awaitImportedModelRestore(operation(1_000), isDisposed = { true }) { it() }
        assertEquals(ImportedModelRestore.DISPOSED, outcome)
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
