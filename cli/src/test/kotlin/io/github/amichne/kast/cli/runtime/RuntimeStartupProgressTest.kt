package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapFailure
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapPhase
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeStartupProgressTest {
    private val attempt = (SemanticRuntimeBootstrapAttemptId.admit(
        "123e4567-e89b-42d3-a456-426614174000",
    ) as Refinement.Refined).value

    @Test
    fun `terminal progress reports all phases elapsed time and throttled heartbeat`() {
        val lines = mutableListOf<String>()
        var now = 0L
        val progress = TerminalRuntimeStartupProgress(true, { now }, lines::add)
        progress.discoveringRuntime()
        val indexing = SemanticRuntimeBootstrapState.Starting(attempt, SemanticRuntimeBootstrapPhase.INDEXING)
        progress.publish(indexing)
        now = 4_000_000_000L
        progress.publish(indexing)
        assertEquals(2, lines.size)
        now = 5_000_000_000L
        progress.publish(indexing)
        assertEquals("kast: indexing; elapsed=5s; progress=3/7 phases", lines.last())
        progress.publish(SemanticRuntimeBootstrapState.Ready(attempt))
        assertEquals("kast: ready; elapsed=5s; progress=7/7 phases", lines.last())
    }

    @Test
    fun `machine mode never emits progress and terminal output has a hard bound`() {
        val machineLines = mutableListOf<String>()
        val machine = TerminalRuntimeStartupProgress(false, { 0L }, machineLines::add)
        machine.discoveringRuntime()
        SemanticRuntimeBootstrapPhase.entries.forEach { machine.publish(SemanticRuntimeBootstrapState.Starting(attempt, it)) }
        machine.publish(SemanticRuntimeBootstrapState.Ready(attempt))
        assertEquals(emptyList<String>(), machineLines)

        val terminalLines = mutableListOf<String>()
        var now = 0L
        val terminal = TerminalRuntimeStartupProgress(true, { now }, terminalLines::add)
        repeat(1000) {
            terminal.publish(SemanticRuntimeBootstrapState.Starting(attempt, SemanticRuntimeBootstrapPhase.INDEXING))
            now += 5_000_000_000L
        }
        assertEquals(255, terminalLines.size)
        terminal.publish(SemanticRuntimeBootstrapState.Ready(attempt))
        assertEquals(256, terminalLines.size)
        assertTrue(terminalLines.last().contains("ready"))
        assertTrue(terminalLines.all { it.length < 200 })
    }

    @Test
    fun `every finite bootstrap rejection names its failed phase and next action`() {
        SemanticRuntimeBootstrapFailure.entries.forEach { cause ->
            val lines = mutableListOf<String>()
            TerminalRuntimeStartupProgress(true, { 0L }, lines::add).publish(
                SemanticRuntimeBootstrapState.Rejected(attempt, cause, SemanticRuntimeBootstrapPhase.GRADLE_JVM_SELECTION),
            )
            assertTrue(lines.single().contains("rejected during selecting Gradle JVM"))
            assertTrue(lines.single().contains("cause=${cause.wireName}; next: "))
            assertTrue(lines.single().length < 400)
        }
    }
}
