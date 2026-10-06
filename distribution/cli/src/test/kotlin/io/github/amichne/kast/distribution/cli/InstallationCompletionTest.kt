package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationShutdownRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationCompletionTest {
    @Test
    fun `active completion publishes the new native executable before resuming an exact shutdown`(@TempDir root: Path) {
        val fixture = createLifecycleFixture(root)
        fence(fixture)
        val script = Script(fixture, Step("bootstrap", LifecycleChildObservation.Exited(0)))
        val events = mutableListOf<LifecycleObservation>()

        val completed =
            assertInstanceOf(InstallationCompletionResult.Published::class.java, complete(fixture, script, events))

        assertEquals(receipt(fixture).executable, completed.destination.path.toString())
        assertFalse(Files.exists(fixture.root.resolve(SHUTDOWN_FENCE)))
        assertEquals(
            listOf(LifecycleObservationOutcome.STARTED, LifecycleObservationOutcome.COMPLETED),
            events.map { it.outcome },
        )
        script.exhausted()
    }

    @Test
    fun `unfenced completion publishes without executing or claiming new activation`(@TempDir root: Path) {
        val fixture = createLifecycleFixture(root)
        val script = Script(fixture)
        val events = mutableListOf<LifecycleObservation>()

        assertInstanceOf(InstallationCompletionResult.Published::class.java, complete(fixture, script, events))

        assertTrue(events.isEmpty())
        assertFalse(Files.exists(fixture.root.resolve(SHUTDOWN_FENCE)))
        script.exhausted()
    }

    @Test
    fun `staged publication preserves the shutdown request without service effects`(@TempDir root: Path) {
        val fixture = createLifecycleFixture(root)
        val fence = fence(fixture)
        val bytes = Files.readString(fence)
        val identity = Files.getAttribute(fence, "unix:ino")
        val script = Script(fixture)
        val events = mutableListOf<LifecycleObservation>()

        assertInstanceOf(
            InstallationCompletionResult.Published::class.java,
            complete(fixture, script, events, InstallationCompletion.STAGED),
        )

        assertEquals(bytes, Files.readString(fence))
        assertEquals(identity, Files.getAttribute(fence, "unix:ino"))
        assertTrue(events.isEmpty())
        script.exhausted()
    }

    @Test
    fun `foreign malformed unsupported and oversized shutdown requests preserve publication and refuse activation`(
        @TempDir root: Path
    ) {
        val rejected: List<(LifecycleFixture) -> String> =
            listOf(
                { "foreign-record" },
                { managementJson.encodeToString(InstallationShutdownRequest("/another/installation")) },
                { fixture ->
                    managementJson.encodeToString(
                        InstallationShutdownRequest(fixture.installation.toString(), schemaVersion = 2)
                    )
                },
                { "x".repeat(4097) },
            )
        for ((index, request) in rejected.withIndex()) {
            val fixture = createLifecycleFixture(Files.createDirectory(root.resolve("case-$index")))
            val raw = request(fixture)
            val fence = Files.writeString(fixture.root.resolve(SHUTDOWN_FENCE), raw)
            val script = Script(fixture)
            val events = mutableListOf<LifecycleObservation>()

            val result =
                assertInstanceOf(
                    InstallationCompletionResult.ActivationRejected::class.java,
                    complete(fixture, script, events),
                )

            assertEquals(LifecycleFailure.FENCE_REJECTED, result.failure)
            assertEquals(receipt(fixture).executable, result.destination.path.toString())
            assertEquals(raw, Files.readString(fence))
            assertEquals(
                LifecycleObservation.rejected(
                    LifecycleOperation.REINSTALL,
                    LifecycleStage.ACTIVATION,
                    LifecycleFailure.FENCE_REJECTED,
                ),
                events.last(),
            )
            script.exhausted()
        }
    }

    @Test
    fun `shutdown links refuse activation without following or removing their targets`(@TempDir root: Path) {
        val fixture = createLifecycleFixture(root)
        val raw = managementJson.encodeToString(InstallationShutdownRequest(fixture.installation.toString()))
        val external = Files.writeString(root.resolve("external"), raw)
        val fence = Files.createSymbolicLink(fixture.root.resolve(SHUTDOWN_FENCE), external)
        val script = Script(fixture)

        val result =
            assertInstanceOf(InstallationCompletionResult.ActivationRejected::class.java, complete(fixture, script))

        assertEquals(LifecycleFailure.FENCE_REJECTED, result.failure)
        assertTrue(Files.isSymbolicLink(fence))
        assertEquals(raw, Files.readString(external))
        script.exhausted()
    }

    @Test
    fun `failed bootstrap restores the shutdown request and retires partial startup`(@TempDir root: Path) {
        val rejections =
            listOf(
                LifecycleChildObservation.Exited(7) to LifecycleFailure.CHILD_REJECTED,
                LifecycleChildObservation.DeadlineExceeded to LifecycleFailure.CHILD_DEADLINE_EXCEEDED,
                LifecycleChildObservation.Unavailable to LifecycleFailure.CHILD_REJECTED,
            )
        for ((index, rejection) in rejections.withIndex()) {
            val fixture = createLifecycleFixture(Files.createDirectory(root.resolve("case-$index")))
            val fence = fence(fixture)
            val bytes = Files.readString(fence)
            val script =
                Script(
                    fixture,
                    Step("bootstrap", rejection.first),
                    Step("disable", LifecycleChildObservation.Exited(0)),
                )
            val events = mutableListOf<LifecycleObservation>()

            val result =
                assertInstanceOf(
                    InstallationCompletionResult.ActivationRejected::class.java,
                    complete(fixture, script, events),
                )

            assertEquals(rejection.second, result.failure)
            assertEquals(bytes, Files.readString(fence))
            assertEquals(
                LifecycleObservation.rejected(
                    LifecycleOperation.REINSTALL,
                    LifecycleStage.ACTIVATION,
                    rejection.second,
                ),
                events.last(),
            )
            script.exhausted()
        }
    }

    private fun fence(fixture: LifecycleFixture): Path =
        Files.writeString(
            fixture.root.resolve(SHUTDOWN_FENCE),
            managementJson.encodeToString(InstallationShutdownRequest(fixture.installation.toString())),
        )

    private fun receipt(fixture: LifecycleFixture): ManagementReceipt =
        assertInstanceOf(ReceiptRead.Read::class.java, readReceipt(fixture.root)).receipt

    private fun complete(
        fixture: LifecycleFixture,
        script: Script,
        events: MutableList<LifecycleObservation> = mutableListOf(),
        completion: InstallationCompletion = InstallationCompletion.ACTIVATE,
    ): InstallationCompletionResult =
        completePublicInstallation(
            root = fixture.root,
            environment = mapOf("HOME" to fixture.home.toString(), "PATH" to "/usr/bin:/bin"),
            channel = ReleaseChannel.DEVELOPER,
            completion = completion,
            execution = LifecycleExecution(child = script, processes = closedHostProcesses(), observe = events::add),
        )

    private data class Step(val command: String, val observation: LifecycleChildObservation)

    private inner class Script(private val fixture: LifecycleFixture, vararg steps: Step) : LifecycleChildExecutor {
        private val remaining = ArrayDeque(steps.toList())

        override fun execute(
            command: List<String>,
            directory: Path,
            environment: Map<String, String>,
        ): LifecycleChildObservation {
            val next = remaining.removeFirstOrNull() ?: throw AssertionError("Unexpected child execution")
            assertEquals(
                listOf(fixture.installation.resolve("share/kast/libexec/kast-service").toString(), next.command),
                command,
            )
            assertEquals(fixture.installation, directory)
            assertEquals(fixture.home.toString(), environment["HOME"])
            assertEquals(
                fixture.installation.resolve("config/environment").toString(),
                environment["KAST_CONFIGURATION_FILE"],
            )
            val source = fixture.installation.resolve("share/kast/libexec/kast-management")
            val published = receipt(fixture)
            assertEquals(sha256(source), published.executableSha256)
            assertEquals(Files.readString(source), Files.readString(Path.of(published.executable)))
            assertEquals(next.command == "disable", Files.exists(fixture.root.resolve(SHUTDOWN_FENCE)))
            return next.observation
        }

        fun exhausted() = assertTrue(remaining.isEmpty(), "Unconsumed child executions")
    }
}
