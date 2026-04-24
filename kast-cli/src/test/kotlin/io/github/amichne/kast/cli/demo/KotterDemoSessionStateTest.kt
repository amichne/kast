package io.github.amichne.kast.cli.demo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KotterDemoSessionStateTest {
    @Test
    fun `switching operations replaces the active scenario and resets to the new operation start`() = runTest {
        val session = startSession(contractConfig())

        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("[rename] resolve target symbol"), session.snapshot().liveTexts())

        session.switchOperation("references")

        val afterSwitch = session.snapshot()
        assertEquals("references", afterSwitch.activeOperationId)
        assertEquals(KotterDemoPhaseStatus.ACTIVE, afterSwitch.phaseStates.getValue("resolve"))
        assertEquals(KotterDemoPhaseStatus.PENDING, afterSwitch.phaseStates.getValue("summarize"))
        assertTrue(afterSwitch.liveLines.isEmpty(), "switching operations should clear in-flight live output")
        assertTrue(afterSwitch.asideLines.isEmpty(), "switching operations should not carry aside output from the abandoned scenario")

        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("[refs] queue semantic references"), session.snapshot().liveTexts())
    }

    @Test
    fun `phase transitions wait for scenario milestones instead of elapsed time alone`() = runTest {
        val session = startSession(contractConfig())

        advanceTimeBy(9_999)
        runCurrent()

        val beforeMilestone = session.snapshot()
        assertEquals(KotterDemoPhaseStatus.ACTIVE, beforeMilestone.phaseStates.getValue("resolve"))
        assertEquals(KotterDemoPhaseStatus.PENDING, beforeMilestone.phaseStates.getValue("traverse"))
        assertTrue(beforeMilestone.asideLines.isEmpty(), "resolve output should stay live until its milestone closes the phase")

        advanceTimeBy(1)
        runCurrent()

        val afterMilestone = session.snapshot()
        assertEquals(KotterDemoPhaseStatus.COMPLETE, afterMilestone.phaseStates.getValue("resolve"))
        assertEquals(KotterDemoPhaseStatus.ACTIVE, afterMilestone.phaseStates.getValue("traverse"))
        assertEquals(
            listOf("[rename] resolve target symbol", "[rename] queue branch fan-out"),
            afterMilestone.asideTexts(),
        )
        assertTrue(afterMilestone.liveLines.isEmpty(), "phase output should flush out of the live region when the milestone lands")
    }

    @Test
    fun `replay restarts the current operation from its first phase`() = runTest {
        val session = startSession(contractConfig())

        session.switchOperation("references")
        advanceTimeBy(300)
        runCurrent()

        val beforeReplay = session.snapshot()
        assertEquals(KotterDemoPhaseStatus.COMPLETE, beforeReplay.phaseStates.getValue("resolve"))
        assertEquals(KotterDemoPhaseStatus.ACTIVE, beforeReplay.phaseStates.getValue("summarize"))
        assertTrue(beforeReplay.allTexts().isNotEmpty(), "expected scripted output before replay")

        session.replay()

        val afterReplay = session.snapshot()
        assertEquals("references", afterReplay.activeOperationId)
        assertEquals(KotterDemoPhaseStatus.ACTIVE, afterReplay.phaseStates.getValue("resolve"))
        assertEquals(KotterDemoPhaseStatus.PENDING, afterReplay.phaseStates.getValue("summarize"))
        assertTrue(afterReplay.liveLines.isEmpty(), "replay should clear current live output before the scenario starts again")
        assertTrue(afterReplay.asideLines.isEmpty(), "replay should clear flushed output before the scenario starts again")

        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("[refs] queue semantic references"), session.snapshot().liveTexts())
    }

    @Test
    fun `switching operations cancels delayed emissions from the abandoned scenario`() = runTest {
        val session = startSession(contractConfig())

        advanceTimeBy(100)
        runCurrent()
        session.switchOperation("references")

        advanceTimeBy(12_000)
        runCurrent()

        val linesAfterSwitch = session.snapshot().allTexts()
        assertFalse(linesAfterSwitch.any { it == "[rename] queue branch fan-out" }, "delayed rename output leaked after switching operations")
        assertFalse(linesAfterSwitch.any { it == "[rename] fan out rename branches" }, "post-switch rename traversal leaked into the replacement scenario")
        assertTrue(linesAfterSwitch.any { it.startsWith("[refs]") }, "expected the replacement scenario to produce output after the switch")
    }

    @Test
    fun `codePreview propagates through scenario events into session state transcript lines`() = runTest {
        val scenario = KotterDemoSessionScenario(
            initialOperationId = "refs",
            operations = listOf(
                KotterDemoOperationScenario(
                    id = "refs",
                    phases = listOf("search"),
                    events = listOf(
                        KotterDemoScenarioEvent.Line(
                            atMillis = 100,
                            phaseId = "search",
                            text = "Walker.kt:93",
                            codePreview = "val cmd = parse(raw)",
                        ),
                        KotterDemoScenarioEvent.Milestone(atMillis = 200, phaseId = "search"),
                    ),
                ),
            ),
        )
        val session = KotterDemoSessionController.createForTest(this, scenario).apply { start() }

        advanceTimeBy(150)
        runCurrent()

        val line = session.snapshot().liveLines.single()
        assertEquals("Walker.kt:93", line.text)
        assertEquals("val cmd = parse(raw)", line.codePreview)
    }

    private fun TestScope.startSession(contract: KotterDemoSessionScenario): KotterDemoSessionController =
        KotterDemoSessionController.createForTest(this, contract).apply { start() }

    private fun contractConfig(): KotterDemoSessionScenario = KotterDemoSessionScenario(
        initialOperationId = "rename",
        operations = listOf(
            KotterDemoOperationScenario(
                id = "rename",
                phases = listOf("resolve", "traverse", "apply"),
                events = listOf(
                    KotterDemoScenarioEvent.Line(atMillis = 100, phaseId = "resolve", text = "[rename] resolve target symbol"),
                    KotterDemoScenarioEvent.Line(atMillis = 5_000, phaseId = "resolve", text = "[rename] queue branch fan-out"),
                    KotterDemoScenarioEvent.Milestone(atMillis = 10_000, phaseId = "resolve"),
                    KotterDemoScenarioEvent.Line(atMillis = 10_100, phaseId = "traverse", text = "[rename] fan out rename branches"),
                    KotterDemoScenarioEvent.Milestone(atMillis = 10_200, phaseId = "traverse"),
                    KotterDemoScenarioEvent.Line(atMillis = 10_300, phaseId = "apply", text = "[rename] apply verified edits"),
                    KotterDemoScenarioEvent.Milestone(atMillis = 10_400, phaseId = "apply"),
                ),
            ),
            KotterDemoOperationScenario(
                id = "references",
                phases = listOf("resolve", "summarize"),
                events = listOf(
                    KotterDemoScenarioEvent.Line(atMillis = 100, phaseId = "resolve", text = "[refs] queue semantic references"),
                    KotterDemoScenarioEvent.Milestone(atMillis = 200, phaseId = "resolve"),
                    KotterDemoScenarioEvent.Line(atMillis = 300, phaseId = "summarize", text = "[refs] summarize call sites"),
                    KotterDemoScenarioEvent.Milestone(atMillis = 400, phaseId = "summarize"),
                ),
            ),
        ),
    )
}
