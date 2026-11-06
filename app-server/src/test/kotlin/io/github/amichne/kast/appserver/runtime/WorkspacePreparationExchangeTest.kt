@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Script
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Step
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCause
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeCommand
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleExchangeStage
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspacePreparationExchangeTest {
    @Test
    fun `admitted preparation survives one read-only exchange deadline without another open`() = runTest {
        for (command in listOf(IdeLifecycleExchangeCommand.INSPECT, IdeLifecycleExchangeCommand.STATUS)) {
            val fixture = WorkspaceRecoveryNativeFixture()
            val script =
                Script(
                    listOf(
                        Step(fixture.open(1)) { fixture.pending(1) },
                        Step(fixture.status(1)) { timeout(command) },
                        Step(fixture.status(1)) { fixture.pending(1) },
                        Step(fixture.status(1)) { IdeLifecycleResult.Opened(fixture.target) },
                    )
                )
            val events = mutableListOf<WorkspacePreparationActivity>()
            val preparations =
                WorkspacePreparations(
                    this,
                    script::exchange,
                    observer = WorkspacePreparationObserver(events::add),
                    newId = { fixture.id(1) },
                )
            try {
                val entry = preparations.prepare(fixture.root).recoveryEntry()
                runCurrent()
                advanceTimeBy(300)
                runCurrent()
                assertEquals(
                    fixture.target,
                    (entry.state.value as WorkspacePreparationOutcome.Complete).workspace.target,
                )
                script.assertDrained()
                val event = events.single {
                    it.outcome is WorkspacePreparationActivityOutcome.WaitingAfterExchangeDeadline
                }
                assertExchangeEvent(event, fixture, command)
            } finally {
                preparations.close()
            }
        }
    }

    @Test
    fun `native rejection after a transient exchange remains terminal`() = runTest {
        for (reason in
            listOf(IdeLifecycleFailure.WRONG_HOST, IdeLifecycleFailure.IMPORT_FAILED, IdeLifecycleFailure.SHUTDOWN)) {
            val fixture = WorkspaceRecoveryNativeFixture()
            val script =
                Script(
                    listOf(
                        Step(fixture.open(1)) { fixture.pending(1) },
                        Step(fixture.status(1)) { timeout(IdeLifecycleExchangeCommand.INSPECT) },
                        Step(fixture.status(1)) { IdeLifecycleResult.Blocked(reason) },
                    )
                )
            val preparations = WorkspacePreparations(this, script::exchange, newId = { fixture.id(1) })
            try {
                val entry = preparations.prepare(fixture.root).recoveryEntry()
                runCurrent()
                advanceTimeBy(200)
                runCurrent()
                assertEquals(WorkspacePreparationOutcome.Blocked(reason), entry.state.value)
                script.assertDrained()
            } finally {
                preparations.close()
            }
        }
    }

    @Test
    fun `an initial open exchange deadline cannot establish pending admission`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val failure = timeout(IdeLifecycleExchangeCommand.OPEN)
        val script = Script(listOf(Step(fixture.open(1)) { failure }))
        val preparations = WorkspacePreparations(this, script::exchange, newId = { fixture.id(1) })
        try {
            val entry = preparations.prepare(fixture.root).recoveryEntry()
            runCurrent()
            assertEquals(
                WorkspacePreparationOutcome.Blocked(failure.reason, exchange = failure.exchange),
                entry.state.value,
            )
            script.assertDrained()
        } finally {
            preparations.close()
        }
    }

    @Test
    fun `repeated observation deadlines spend the unchanged owner allowance`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val script =
            Script(
                listOf(
                    Step(fixture.open(1)) { fixture.pending(1) },
                    Step(fixture.status(1)) { timeout(IdeLifecycleExchangeCommand.INSPECT) },
                    Step(fixture.status(1)) { timeout(IdeLifecycleExchangeCommand.STATUS) },
                )
            )
        val preparations =
            WorkspacePreparations(
                this,
                script::exchange,
                budget = 250.milliseconds,
                newId = { fixture.id(1) },
                nanoTime = { currentTime * 1_000_000 },
            )
        try {
            val entry = preparations.prepare(fixture.root).recoveryEntry()
            runCurrent()
            advanceTimeBy(250)
            runCurrent()
            assertEquals(
                WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED),
                entry.state.value,
            )
            script.assertDrained()
        } finally {
            preparations.close()
        }
    }

    @Test
    fun `malformed and non-timeout exchange failures cannot authorize another status`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val deadline = timeout(IdeLifecycleExchangeCommand.STATUS)
        val details = checkNotNull(deadline.exchange)
        for (failure in
            listOf(
                deadline.copy(exchange = null),
                deadline.copy(exchange = details.copy(cause = IdeLifecycleExchangeCause.INVALID_RESPONSE)),
                deadline.copy(exchange = details.copy(command = IdeLifecycleExchangeCommand.OPEN)),
                IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE),
            )) {
            val script =
                Script(
                    listOf(
                        Step(fixture.open(1)) { fixture.pending(1) },
                        Step(fixture.status(1)) { failure },
                    )
                )
            val preparations = WorkspacePreparations(this, script::exchange, newId = { fixture.id(1) })
            try {
                val entry = preparations.prepare(fixture.root).recoveryEntry()
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                assertEquals(
                    WorkspacePreparationOutcome.Blocked(failure.reason, exchange = failure.exchange),
                    entry.state.value,
                )
                script.assertDrained()
            } finally {
                preparations.close()
            }
        }
    }

    @Test
    fun `a replaced host in pending progress cannot inherit the admitted request`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val script =
            Script(
                listOf(
                    Step(fixture.open(1)) { fixture.pending(1) },
                    Step(fixture.status(1)) { timeout(IdeLifecycleExchangeCommand.STATUS) },
                    Step(fixture.status(1)) { fixture.pending(1).copy(host = "00000000-0000-0000-0000-000000000099") },
                )
            )
        val preparations = WorkspacePreparations(this, script::exchange, newId = { fixture.id(1) })
        try {
            val entry = preparations.prepare(fixture.root).recoveryEntry()
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(
                WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.RESPONSE_REJECTED),
                entry.state.value,
            )
            script.assertDrained()
        } finally {
            preparations.close()
        }
    }

    @Test
    fun `owner cancellation ends the pending wait after a status deadline`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val script =
            Script(
                listOf(
                    Step(fixture.open(1)) { fixture.pending(1) },
                    Step(fixture.status(1)) { timeout(IdeLifecycleExchangeCommand.STATUS) },
                )
            )
        val preparations = WorkspacePreparations(this, script::exchange, newId = { fixture.id(1) })
        val entry = preparations.prepare(fixture.root).recoveryEntry()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(fixture.pending(1).stage, (entry.state.value as WorkspacePreparationOutcome.Pending).stage)
        preparations.close()
        assertEquals(WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.CLOSED), entry.state.value)
        script.assertDrained()
    }

    private val json = Json { encodeDefaults = true }

    private fun assertExchangeEvent(
        event: WorkspacePreparationActivity,
        fixture: WorkspaceRecoveryNativeFixture,
        command: IdeLifecycleExchangeCommand,
    ) {
        val encoded = Json {
            encodeDefaults = true
        }
            .encodeToJsonElement(WorkspacePreparationActivity.serializer(), event)
            .jsonObject
        assertEquals(setOf("requestId", "outcome", "event", "preparation", "exchange"), encoded.keys)
        assertEquals(fixture.id(1).value.toString(), encoded.getValue("requestId").jsonPrimitive.content)
        val outcome = encoded.getValue("outcome").jsonObject
        assertEquals(setOf("type", "nativeRequestId", "host"), outcome.keys)
        assertEquals("waiting_after_exchange_deadline", outcome.getValue("type").jsonPrimitive.content)
        assertEquals(fixture.id(1).value.toString(), outcome.getValue("nativeRequestId").jsonPrimitive.content)
        assertEquals(fixture.target.host, outcome.getValue("host").jsonPrimitive.content)
        val exchange = encoded.getValue("exchange").jsonObject
        assertEquals(setOf("command", "stage", "cause", "elapsedMillis", "limitMillis"), exchange.keys)
        assertEquals(command.name, exchange.getValue("command").jsonPrimitive.content)
        assertEquals("READ_LENGTH", exchange.getValue("stage").jsonPrimitive.content)
        assertEquals("DEADLINE_EXCEEDED", exchange.getValue("cause").jsonPrimitive.content)
        assertEquals("5000", exchange.getValue("elapsedMillis").jsonPrimitive.content)
        assertEquals("5000", exchange.getValue("limitMillis").jsonPrimitive.content)
    }

    private fun timeout(command: IdeLifecycleExchangeCommand) =
        IdeLifecycleResult.Blocked(
            IdeLifecycleFailure.EXCHANGE_DEADLINE_EXCEEDED,
            exchange =
                IdeLifecycleExchangeFailure(
                    command,
                    IdeLifecycleExchangeStage.READ_LENGTH,
                    IdeLifecycleExchangeCause.DEADLINE_EXCEEDED,
                    5000,
                    5000,
                ),
        )
}
