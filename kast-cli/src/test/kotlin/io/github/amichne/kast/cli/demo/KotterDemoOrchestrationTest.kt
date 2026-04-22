package io.github.amichne.kast.cli.demo

import com.varabyte.kotter.foundation.input.Key
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.runtime.terminal.Terminal
import com.varabyte.kotter.runtime.terminal.inmemory.InMemoryTerminal
import com.varabyte.kotter.runtime.terminal.inmemory.press
import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.runtime.stripFormatting
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

class KotterDemoOrchestrationTest {
    @Test
    fun `key bindings dispatch operation switching replay and quit`() {
        val presentation = sessionPresentation()
        val subject = KotterDemoKeyBindings(presentation)

        assertEquals(
            KotterDemoCommand.SwitchOperation("references"),
            subject.commandFor(Keys.F, activeOperationId = "rename"),
        )
        assertEquals(
            KotterDemoCommand.Replay,
            subject.commandFor(Keys.N, activeOperationId = "rename"),
        )
        assertEquals(
            KotterDemoCommand.Replay,
            subject.commandFor(Keys.R, activeOperationId = "rename"),
        )
        assertEquals(
            KotterDemoCommand.Quit,
            subject.commandFor(Keys.ESC, activeOperationId = "rename"),
        )
        assertEquals(
            KotterDemoCommand.Quit,
            subject.commandFor(Keys.Q, activeOperationId = "rename"),
        )
    }

    @Test
    fun `completed phase output accumulates in rolling transcript alongside live line`() {
        val terminal = TestInMemoryTerminal()
        val runner = startSession(terminal, sessionPresentation())

        waitUntil {
            terminal.visibleLines().any { "[rename] fan out rename branches" in it }
        }

        val lines = terminal.visibleLines()
        assertTrue(lines.any { "[rename] resolve target symbol" in it }, "expected completed resolve output in rolling transcript")
        assertTrue(lines.any { "[rename] fan out rename branches" in it }, "expected active traversal output in rolling transcript")

        terminal.press(Keys.Q)
        assertSessionStopped(runner)
    }

    @Test
    fun `switch shortcut clears the current run and restarts with the selected operation`() {
        val terminal = TestInMemoryTerminal()
        val runner = startSession(terminal, sessionPresentation())

        waitUntil {
            terminal.visibleLines().any { "[rename] resolve target symbol" in it }
        }

        terminal.press(Keys.F)

        waitUntil {
            val lines = terminal.visibleLines()
            lines.any { "[refs] queue semantic references" in it } &&
                lines.none { "[rename] resolve target symbol" in it }
        }

        terminal.press(Keys.Q)
        assertSessionStopped(runner)
    }

    @Test
    fun `replay clears flushed history and restarts the active operation from its first phase`() {
        val terminal = TestInMemoryTerminal()
        val runner = startSession(terminal, sessionPresentation())

        waitUntil {
            terminal.visibleLines().any { "[rename] apply verified edits" in it }
        }

        terminal.press(Keys.R)

        waitUntil {
            val lines = terminal.visibleLines()
            lines.none { "[rename] fan out rename branches" in it } &&
                lines.none { "[rename] apply verified edits" in it }
        }
        waitUntil {
            terminal.visibleLines().any { "[rename] resolve target symbol" in it }
        }

        terminal.press(Keys.Q)
        assertSessionStopped(runner)
    }

    @Test
    fun `narrow terminals hard stop with warning instead of starting playback`() {
        val terminal = FixedWidthTerminal(width = 96)
        val runner = startSession(terminal, branchHeavyPresentation())

        waitUntil {
            terminal.visibleLines().any { "Kotter demo halted" in it }
        }

        val lines = terminal.visibleLines()
        assertTrue(lines.any { "too narrow for faithful Kotter demo rendering" in it })
        assertFalse(lines.any { "[rename] resolve target symbol" in it }, "playback should not start when the terminal is too narrow")

        terminal.press(Keys.Q)
        assertSessionStopped(runner)
    }

    private fun startSession(
        terminal: DemoTestTerminal,
        presentation: KotterDemoSessionPresentation,
    ): Thread = thread(start = true) {
        session(terminal) {
            runKotterDemoSession(
                presentation = presentation,
                terminalWidth = terminal.width,
                clearScreen = terminal::clear,
                blinkInterval = 25.milliseconds,
            )
        }
    }

    private fun assertSessionStopped(runner: Thread) {
        runner.join(2_000)
        assertFalse(runner.isAlive, "expected demo session to terminate")
    }

    private fun waitUntil(
        timeoutMillis: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val startNanos = System.nanoTime()
        while (!condition()) {
            if ((System.nanoTime() - startNanos) / 1_000_000 > timeoutMillis) {
                error("Timed out waiting for terminal output to settle")
            }
            Thread.sleep(10)
        }
    }

    private fun sessionPresentation(): KotterDemoSessionPresentation = KotterDemoSessionPresentation(
        scenario = KotterDemoSessionScenario(
            initialOperationId = "rename",
            operations = listOf(
                KotterDemoOperationScenario(
                    id = "rename",
                    phases = listOf("resolve", "traverse", "apply"),
                    events = listOf(
                        KotterDemoScenarioEvent.Line(atMillis = 25, phaseId = "resolve", text = "[rename] resolve target symbol"),
                        KotterDemoScenarioEvent.Milestone(atMillis = 50, phaseId = "resolve"),
                        KotterDemoScenarioEvent.Line(atMillis = 75, phaseId = "traverse", text = "[rename] fan out rename branches"),
                        KotterDemoScenarioEvent.Milestone(atMillis = 100, phaseId = "traverse"),
                        KotterDemoScenarioEvent.Line(atMillis = 125, phaseId = "apply", text = "[rename] apply verified edits"),
                        KotterDemoScenarioEvent.Milestone(atMillis = 150, phaseId = "apply"),
                    ),
                ),
                KotterDemoOperationScenario(
                    id = "references",
                    phases = listOf("resolve", "summarize"),
                    events = listOf(
                        KotterDemoScenarioEvent.Line(atMillis = 25, phaseId = "resolve", text = "[refs] queue semantic references"),
                        KotterDemoScenarioEvent.Milestone(atMillis = 50, phaseId = "resolve"),
                        KotterDemoScenarioEvent.Line(atMillis = 75, phaseId = "summarize", text = "[refs] summarize call sites"),
                        KotterDemoScenarioEvent.Milestone(atMillis = 100, phaseId = "summarize"),
                    ),
                ),
            ),
        ),
        operations = listOf(
            KotterDemoOperationPresentation(
                id = "rename",
                label = "Rename",
                shortcutKey = 'n',
                query = "kast rename --symbol io.acme.demo.execute --new-name runSemanticDemo",
            ),
            KotterDemoOperationPresentation(
                id = "references",
                label = "Find References",
                shortcutKey = 'f',
                query = "kast references --symbol io.acme.demo.execute --depth 2",
            ),
        ),
    )

    private fun branchHeavyPresentation(): KotterDemoSessionPresentation =
        sessionPresentation().copy(
            operations = listOf(
                KotterDemoOperationPresentation(
                    id = "rename",
                    label = "Rename",
                    shortcutKey = 'n',
                    query = "kast rename --symbol io.acme.demo.execute --new-name runSemanticDemo",
                    branches = listOf(
                        KotterDemoBranchSpec("A.kt", lines = listOf("line"), summary = "done"),
                        KotterDemoBranchSpec("B.kt", lines = listOf("line"), summary = "done"),
                        KotterDemoBranchSpec("C.kt", lines = listOf("line"), summary = "done"),
                        KotterDemoBranchSpec("D.kt", lines = listOf("line"), summary = "done"),
                    ),
                ),
                KotterDemoOperationPresentation(
                    id = "references",
                    label = "Find References",
                    shortcutKey = 'f',
                    query = "kast references --symbol io.acme.demo.execute --depth 2",
                ),
            ),
        )

    private interface DemoTestTerminal : Terminal {
        fun visibleLines(): List<String>

        fun press(vararg keys: Key)
    }

    private class TestInMemoryTerminal : DemoTestTerminal {
        private val delegate = InMemoryTerminal()

        override val width: Int = delegate.width
        override val height: Int = delegate.height

        override fun write(text: String) = delegate.write(text)

        override fun read(): SharedFlow<Int> = delegate.read()

        override fun clear() = delegate.clear()

        override fun close() = delegate.close()

        override fun visibleLines(): List<String> = delegate.resolveRerenders().stripFormatting()

        override fun press(vararg keys: Key) {
            runBlocking {
                delegate.press(*keys)
            }
        }
    }

    private class FixedWidthTerminal(
        override val width: Int,
    ) : DemoTestTerminal {
        private val delegate = InMemoryTerminal()

        override val height: Int = delegate.height

        override fun write(text: String) = delegate.write(text)

        override fun read(): SharedFlow<Int> = delegate.read()

        override fun clear() = delegate.clear()

        override fun close() = delegate.close()

        override fun visibleLines(): List<String> = delegate.resolveRerenders().stripFormatting()

        override fun press(vararg keys: Key) {
            runBlocking {
                delegate.press(*keys)
            }
        }
    }
}
