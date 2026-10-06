package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class NativeLifecycleChildExecutorTest {
    @Test
    @Timeout(10)
    fun `service execution reports exact exit and drains large child output without exposing it`(@TempDir root: Path) {
        val script = root.resolve("service-child.sh")
        Files.writeString(
            script,
            """
            index=0
            while [ "${'$'}index" -lt 2048 ]; do
              printf '%s\n' 'private-source-payload-private-configuration-secret'
              printf '%s\n' 'private-source-payload-private-configuration-secret' >&2
              index=${'$'}((index + 1))
            done
            exit 7
            """
                .trimIndent(),
        )
        val events = mutableListOf<LifecycleChildExecutionObservation>()

        val result =
            NativeLifecycleChildExecutor.executeBounded(
                command = listOf("/bin/sh", script.toString(), "bootstrap"),
                directory = root,
                environment = emptyMap(),
                purpose = LifecycleChildPurpose.SERVICE,
                observe = events::add,
            )

        assertEquals(LifecycleChildObservation.Exited(7), result)
        assertEquals(
            listOf(
                LifecycleChildExecutionObservation.Started(
                    LifecycleChildAction.BOOTSTRAP,
                    LifecycleChildPurpose.SERVICE,
                ),
                LifecycleChildExecutionObservation.Exited(
                    action = LifecycleChildAction.BOOTSTRAP,
                    purpose = LifecycleChildPurpose.SERVICE,
                    exitCode = 7,
                ),
            ),
            events,
        )
        for (event in events) {
            val encoded = managementJson.encodeToString<LifecycleChildExecutionObservation>(event)
            assertFalse(encoded.contains("private-"))
            assertFalse(encoded.contains(root.toString()))
        }
    }

    @Test
    fun `successful child execution retains an explicit zero exit signal`(@TempDir root: Path) {
        val events = mutableListOf<LifecycleChildExecutionObservation>()

        val result =
            NativeLifecycleChildExecutor.executeBounded(
                command = listOf("/bin/sh", "-c", "exit 0", "disable"),
                directory = root,
                environment = emptyMap(),
                purpose = LifecycleChildPurpose.SERVICE,
                observe = events::add,
            )

        assertEquals(LifecycleChildObservation.Exited(0), result)
        assertEquals(
            listOf(
                LifecycleChildExecutionObservation.Started(LifecycleChildAction.DISABLE, LifecycleChildPurpose.SERVICE),
                LifecycleChildExecutionObservation.Exited(
                    action = LifecycleChildAction.DISABLE,
                    purpose = LifecycleChildPurpose.SERVICE,
                    exitCode = 0,
                ),
            ),
            events,
        )
    }

    @Test
    fun `unavailable executable reports launch rejection without exposing its command or environment`(
        @TempDir root: Path
    ) {
        val events = mutableListOf<LifecycleChildExecutionObservation>()

        val result =
            NativeLifecycleChildExecutor.executeBounded(
                command = listOf(root.resolve("private-missing-command").toString(), "bootstrap"),
                directory = root,
                environment = mapOf("PRIVATE_CONFIGURATION" to "private-secret"),
                purpose = LifecycleChildPurpose.SERVICE,
                observe = events::add,
            )

        assertEquals(LifecycleChildObservation.Unavailable, result)
        assertEquals(
            listOf(
                LifecycleChildExecutionObservation.Started(
                    LifecycleChildAction.BOOTSTRAP,
                    LifecycleChildPurpose.SERVICE,
                ),
                LifecycleChildExecutionObservation.Unavailable(
                    action = LifecycleChildAction.BOOTSTRAP,
                    purpose = LifecycleChildPurpose.SERVICE,
                    failure = LifecycleChildUnavailability.START_REJECTED,
                ),
            ),
            events,
        )
        for (event in events) {
            assertFalse(managementJson.encodeToString<LifecycleChildExecutionObservation>(event).contains("private-"))
        }
    }
}
