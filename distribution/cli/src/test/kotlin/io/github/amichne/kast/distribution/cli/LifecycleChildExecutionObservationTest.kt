package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LifecycleChildExecutionObservationTest {
    @Test
    fun `child observations encode finite stages outcomes and exit codes with no raw diagnostics`() {
        val observations =
            listOf(
                LifecycleChildExecutionObservation.Started(
                    LifecycleChildAction.BOOTSTRAP,
                    LifecycleChildPurpose.SERVICE,
                ),
                LifecycleChildExecutionObservation.Exited(
                    action = LifecycleChildAction.BOOTSTRAP,
                    purpose = LifecycleChildPurpose.SERVICE,
                    exitCode = 64,
                ),
                LifecycleChildExecutionObservation.DeadlineExceeded(
                    LifecycleChildAction.INSTALL,
                    LifecycleChildPurpose.INSTALLER,
                ),
                LifecycleChildExecutionObservation.Unavailable(
                    action = LifecycleChildAction.DISABLE,
                    purpose = LifecycleChildPurpose.SERVICE,
                    failure = LifecycleChildUnavailability.START_REJECTED,
                ),
                LifecycleChildExecutionObservation.Unavailable(
                    action = LifecycleChildAction.DISABLE,
                    purpose = LifecycleChildPurpose.SERVICE,
                    failure = LifecycleChildUnavailability.OBSERVATION_REJECTED,
                ),
                LifecycleChildExecutionObservation.Unavailable(
                    action = LifecycleChildAction.DISABLE,
                    purpose = LifecycleChildPurpose.SERVICE,
                    failure = LifecycleChildUnavailability.INTERRUPTED,
                ),
            )
        val expected =
            Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/management/lifecycle-child-expected-shapes.json")).readText()
                )
                .jsonArray
                .map { it.toString() }

        assertEquals(
            expected,
            observations.map { managementJson.encodeToString<LifecycleChildExecutionObservation>(it) },
        )
    }

    @Test
    fun `terminal evidence preserves the existing child result without restarting or manufacturing success`() {
        val observations: List<LifecycleChildExecutionTerminal> =
            listOf(
                LifecycleChildExecutionObservation.Exited(
                    action = LifecycleChildAction.BOOTSTRAP,
                    purpose = LifecycleChildPurpose.SERVICE,
                    exitCode = 0,
                ),
                LifecycleChildExecutionObservation.Exited(
                    action = LifecycleChildAction.BOOTSTRAP,
                    purpose = LifecycleChildPurpose.SERVICE,
                    exitCode = 64,
                ),
                LifecycleChildExecutionObservation.DeadlineExceeded(
                    LifecycleChildAction.INSTALL,
                    LifecycleChildPurpose.INSTALLER,
                ),
                LifecycleChildExecutionObservation.Unavailable(
                    action = LifecycleChildAction.BOOTSTRAP,
                    purpose = LifecycleChildPurpose.SERVICE,
                    failure = LifecycleChildUnavailability.START_REJECTED,
                ),
            )

        assertEquals(
            listOf(
                LifecycleChildObservation.Exited(0),
                LifecycleChildObservation.Exited(64),
                LifecycleChildObservation.DeadlineExceeded,
                LifecycleChildObservation.Unavailable,
            ),
            observations.map { it.asChildObservation() },
        )
    }

    @Test
    fun `unsupported action diagnostics omit arbitrary arguments and installer purpose remains authoritative`() {
        val command = listOf("private-executable", "private-source-secret")
        val action = lifecycleChildAction(command, LifecycleChildPurpose.SERVICE)

        assertEquals(LifecycleChildAction.UNSUPPORTED, action)
        assertEquals(LifecycleChildAction.INSTALL, lifecycleChildAction(command, LifecycleChildPurpose.INSTALLER))
        assertFalse(
            managementJson
                .encodeToString<LifecycleChildExecutionObservation>(
                    LifecycleChildExecutionObservation.Started(action, LifecycleChildPurpose.SERVICE)
                )
                .contains("private-")
        )
    }
}
