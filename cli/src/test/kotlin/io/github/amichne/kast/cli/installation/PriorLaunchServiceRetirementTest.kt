package io.github.amichne.kast.cli.installation

import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PriorLaunchServiceRetirementTest {
    @Test
    fun `nonzero bootout succeeds only after the exact service is observed absent`() {
        val script =
            ScriptedLaunchctl(
                listOf("list", SERVICE) to PriorLaunchctlResult.Completed(),
                listOf("bootout", "gui/501/$SERVICE") to PriorLaunchctlResult.ExitRejected(5),
                listOf("list", SERVICE) to PriorLaunchctlResult.Absent(),
            )
        val observations = mutableListOf<PriorLaunchctlObservation>()
        val result =
            retireLaunchService(
                service = SERVICE,
                uid = 501,
                executor = script,
                observe = observations::add,
                timing = noWaiting(),
            )

        assertAll(
            { assertEquals(InstallationChildOutcome.COMPLETED, result) },
            script::assertConsumed,
            {
                assertEquals(
                    listOf(
                        PriorLaunchctlObservation(
                            stage = PriorLaunchctlStage.OBSERVE_BEFORE,
                            outcome = PriorLaunchctlResult.Completed(),
                        ),
                        PriorLaunchctlObservation(
                            stage = PriorLaunchctlStage.BOOTOUT,
                            outcome = PriorLaunchctlResult.ExitRejected(5),
                        ),
                        PriorLaunchctlObservation(
                            stage = PriorLaunchctlStage.OBSERVE_AFTER,
                            outcome = PriorLaunchctlResult.Absent(),
                        ),
                    ),
                    observations,
                )
            },
        )
    }

    @Test
    fun `successful bootout waits for delayed service absence without repeating bootout`() {
        val script =
            ScriptedLaunchctl(
                listOf("list", SERVICE) to PriorLaunchctlResult.Completed(),
                listOf("bootout", "gui/501/$SERVICE") to PriorLaunchctlResult.Completed(),
                listOf("list", SERVICE) to PriorLaunchctlResult.Completed(),
                listOf("list", SERVICE) to PriorLaunchctlResult.Absent(),
            )
        val observations = mutableListOf<PriorLaunchctlObservation>()
        var elapsedNanos = 0L
        val pauses = mutableListOf<Duration>()
        val result =
            retireLaunchService(
                service = SERVICE,
                uid = 501,
                executor = script,
                observe = observations::add,
                timing =
                    PriorRetirementTiming(
                        clock = { elapsedNanos },
                        pause = { duration ->
                            pauses += duration
                            elapsedNanos += duration.toNanos()
                            PriorRetirementPause.CONTINUE
                        },
                    ),
            )

        assertAll(
            { assertEquals(InstallationChildOutcome.COMPLETED, result) },
            script::assertConsumed,
            { assertEquals(listOf(Duration.ofMillis(100)), pauses) },
            { assertEquals(PriorLaunchctlResult.Absent(), observations.last().outcome) },
        )
    }

    @Test
    fun `nonzero bootout cannot complete while the service remains present through the deadline`() {
        val script =
            ScriptedLaunchctl(
                listOf("list", SERVICE) to PriorLaunchctlResult.Completed(),
                listOf("bootout", "gui/501/$SERVICE") to PriorLaunchctlResult.ExitRejected(5),
                listOf("list", SERVICE) to PriorLaunchctlResult.Completed(),
            )
        val observations = mutableListOf<PriorLaunchctlObservation>()
        var elapsedNanos = 0L
        val result =
            retireLaunchService(
                service = SERVICE,
                uid = 501,
                executor = script,
                observe = observations::add,
                timing =
                    PriorRetirementTiming(
                        clock = { elapsedNanos },
                        pause = {
                            elapsedNanos = TimeUnit.SECONDS.toNanos(10)
                            PriorRetirementPause.CONTINUE
                        },
                    ),
            )

        assertAll(
            { assertEquals(InstallationChildOutcome.DEADLINE_EXCEEDED, result) },
            script::assertConsumed,
            { assertEquals(PriorLaunchctlResult.ExitRejected(5), observations[1].outcome) },
            {
                assertEquals(
                    PriorLaunchctlObservation(
                        stage = PriorLaunchctlStage.OBSERVE_AFTER,
                        outcome = PriorLaunchctlResult.DeadlineExceeded,
                    ),
                    observations.last(),
                )
            },
        )
    }

    @Test
    fun `unknown initial observation rejects without signaling the service`() {
        val script = ScriptedLaunchctl(listOf("list", SERVICE) to PriorLaunchctlResult.ExitRejected(99))
        val observations = mutableListOf<PriorLaunchctlObservation>()

        assertEquals(
            InstallationChildOutcome.EXIT_REJECTED,
            retireLaunchService(
                service = SERVICE,
                uid = 501,
                executor = script,
                observe = observations::add,
                timing = noWaiting(),
            ),
        )
        script.assertConsumed()
        assertEquals(
            listOf(
                PriorLaunchctlObservation(
                    stage = PriorLaunchctlStage.OBSERVE_BEFORE,
                    outcome = PriorLaunchctlResult.ExitRejected(99),
                )
            ),
            observations,
        )
    }

    @Test
    fun `rejected observations after bootout preserve every finite failure without waiting`() {
        for (failure in
            listOf(
                PriorLaunchctlResult.ExitRejected(99),
                PriorLaunchctlResult.IoRejected,
                PriorLaunchctlResult.DeadlineExceeded,
                PriorLaunchctlResult.Interrupted,
            )) {
            val script =
                ScriptedLaunchctl(
                    listOf("list", SERVICE) to PriorLaunchctlResult.Completed(),
                    listOf("bootout", "gui/501/$SERVICE") to PriorLaunchctlResult.Completed(),
                    listOf("list", SERVICE) to failure,
                )
            val observations = mutableListOf<PriorLaunchctlObservation>()
            val result =
                retireLaunchService(
                    service = SERVICE,
                    uid = 501,
                    executor = script,
                    observe = observations::add,
                    timing = noWaiting(),
                )

            assertAll(
                { assertEquals(failure.outcome, result) },
                script::assertConsumed,
                {
                    assertEquals(
                        PriorLaunchctlObservation(stage = PriorLaunchctlStage.OBSERVE_AFTER, outcome = failure),
                        observations.last(),
                    )
                },
            )
        }
    }

    @Test
    fun `interrupted wait preserves interruption without another launchctl invocation`() {
        val script =
            ScriptedLaunchctl(
                listOf("list", SERVICE) to PriorLaunchctlResult.Completed(),
                listOf("bootout", "gui/501/$SERVICE") to PriorLaunchctlResult.Completed(),
                listOf("list", SERVICE) to PriorLaunchctlResult.Completed(),
            )
        val observations = mutableListOf<PriorLaunchctlObservation>()
        val result =
            retireLaunchService(
                service = SERVICE,
                uid = 501,
                executor = script,
                observe = observations::add,
                timing = PriorRetirementTiming(clock = { 0 }, pause = { PriorRetirementPause.INTERRUPTED }),
            )

        assertAll(
            { assertEquals(InstallationChildOutcome.INTERRUPTED, result) },
            script::assertConsumed,
            {
                assertEquals(
                    PriorLaunchctlObservation(
                        stage = PriorLaunchctlStage.OBSERVE_AFTER,
                        outcome = PriorLaunchctlResult.Interrupted,
                    ),
                    observations.last(),
                )
            },
        )
    }

    @Test
    fun `launchctl telemetry encodes closed outcomes and exit codes without arguments`() {
        val exited =
            listOf(
                Triple(PriorLaunchctlResult.Completed(), "COMPLETED", 0),
                Triple(PriorLaunchctlResult.Absent(), "ABSENT", 113),
                Triple(PriorLaunchctlResult.ExitRejected(5), "EXIT_REJECTED", 5),
            )
        for ((outcome, expectedType, expectedCode) in exited) {
            val encoded = assertTelemetry(outcome)
            assertEquals(setOf("type", "exitCode"), encoded.keys)
            assertEquals(expectedType, encoded.getValue("type").jsonPrimitive.content)
            assertEquals(expectedCode, encoded.getValue("exitCode").jsonPrimitive.int)
        }
        val notExited =
            listOf(
                PriorLaunchctlResult.DeadlineExceeded to "DEADLINE_EXCEEDED",
                PriorLaunchctlResult.IoRejected to "IO_REJECTED",
                PriorLaunchctlResult.Interrupted to "INTERRUPTED",
            )
        for ((outcome, expectedType) in notExited) {
            val encoded = assertTelemetry(outcome)
            assertEquals(setOf("type"), encoded.keys)
            assertEquals(expectedType, encoded.getValue("type").jsonPrimitive.content)
        }
    }

    private fun assertTelemetry(outcome: PriorLaunchctlResult): JsonObject {
        val encoded =
            Json.parseToJsonElement(
                    PriorLaunchctlObservation(stage = PriorLaunchctlStage.OBSERVE_AFTER, outcome = outcome).toJson()
                )
                .jsonObject
        assertEquals(setOf("event", "stage", "outcome"), encoded.keys)
        assertEquals("kast_installation_retirement", encoded.getValue("event").jsonPrimitive.content)
        assertEquals("OBSERVE_AFTER", encoded.getValue("stage").jsonPrimitive.content)
        return encoded.getValue("outcome").jsonObject
    }

    private fun noWaiting() = PriorRetirementTiming(clock = { 0 }, pause = { error("unexpected wait") })

    private class ScriptedLaunchctl(vararg expectations: Pair<List<String>, PriorLaunchctlResult>) :
        PriorLaunchctlExecutor {
        private val remaining = ArrayDeque(expectations.toList())

        override fun execute(arguments: List<String>, timeout: Duration): PriorLaunchctlResult {
            assertTrue(remaining.isNotEmpty(), "unexpected launchctl invocation: $arguments")
            val (expectedArguments, outcome) = remaining.removeFirst()
            assertEquals(expectedArguments, arguments)
            assertTrue(timeout > Duration.ZERO && timeout <= Duration.ofSeconds(10), "unbounded invocation")
            return outcome
        }

        fun assertConsumed() {
            assertTrue(remaining.isEmpty(), "unconsumed launchctl expectations: $remaining")
        }
    }

    private companion object {
        const val SERVICE = "io.github.amichne.kast.broker.0123456789abcdef0123456789abcdef"
    }
}
