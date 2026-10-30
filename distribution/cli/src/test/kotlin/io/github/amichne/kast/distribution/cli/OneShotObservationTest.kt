package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OneShotObservationTest {
    @Test
    fun `unknown process start never establishes stale record completion`(@TempDir temporary: Path) {
        val installation = qualifyFixture(temporary)
        val directory = Files.createDirectories(installation.resolve("state/run/one-shot"))
        Files.writeString(
            directory.resolve("unknown.json"),
            managementJson.encodeToString(InstalledToolInvocation(1, 42, 99)),
        )
        val current = ProcessHandle.current()
        val process =
            object : ProcessHandle by current {
                override fun info() =
                    object : ProcessHandle.Info by current.info() {
                        override fun startInstant(): Optional<Instant> = Optional.empty()

                        override fun command(): Optional<String> = error("unexpected command read")
                    }
            }
        assertEquals(
            ObservationState.UNAVAILABLE,
            observeOneShotRequests(installation) { NativeLifecycleProcesses.observe(process) }.state,
        )
    }

    @Test
    fun `stale process start survives missing command or arguments while matching identity rejects`(
        @TempDir temporary: Path
    ) {
        val installation = qualifyFixture(temporary)
        val directory = Files.createDirectories(installation.resolve("state/run/one-shot"))
        val entry = directory.resolve("former.json")
        val current = ProcessHandle.current()
        for (missingCommand in listOf(true, false)) {
            val process =
                object : ProcessHandle by current {
                    override fun pid() = 42L

                    override fun isAlive() = true

                    override fun info() =
                        object : ProcessHandle.Info by current.info() {
                            override fun startInstant(): Optional<Instant> = Optional.of(Instant.ofEpochMilli(100))

                            override fun command(): Optional<String> =
                                if (missingCommand) Optional.empty() else Optional.of("/foreign/process")

                            override fun arguments(): Optional<Array<String>> = Optional.empty()
                        }
                }
            Files.writeString(entry, managementJson.encodeToString(InstalledToolInvocation(1, 42, 99)))
            val bytes = Files.readString(entry)
            assertEquals(
                Observation.verified(0),
                observeOneShotRequests(installation) { NativeLifecycleProcesses.observe(process) },
            )
            assertEquals(bytes, Files.readString(entry))
            Files.writeString(entry, managementJson.encodeToString(InstalledToolInvocation(1, 42, 100)))
            assertEquals(
                ObservationState.UNAVAILABLE,
                observeOneShotRequests(installation) { NativeLifecycleProcesses.observe(process) }.state,
            )
        }
    }

    @Test
    fun `verified one-shot records count only the owned live process incarnation`(@TempDir temporary: Path) {
        val installation = qualifyFixture(temporary)
        val directory = installation.resolve("state/run/one-shot")
        assertEquals(Observation.verified(0), observeOneShotRequests(installation))
        Files.createDirectories(directory)
        val snapshot =
            LifecycleProcessSnapshot(
                42,
                100,
                "/fixture/bin/java",
                listOf(
                    "-cp",
                    installation.resolve("lib/kast.jar").toString(),
                    "io.github.amichne.kast.cli.rpc.KastToolRpcMain",
                ),
            )
        Files.writeString(
            directory.resolve("active.json"),
            managementJson.encodeToString(InstalledToolInvocation(1, 42, 100)),
        )
        Files.writeString(
            directory.resolve("former.json"),
            managementJson.encodeToString(InstalledToolInvocation(1, 42, 99)),
        )
        var observations = 0
        assertEquals(
            Observation.verified(1),
            observeOneShotRequests(installation) { pid ->
                assertEquals(42, pid)
                observations++
                LifecycleProcessObservation.Present(snapshot)
            },
        )
        assertEquals(2, observations)
        Files.writeString(
            directory.resolve("active.json"),
            managementJson.encodeToString(InstalledToolInvocation(99, 42, 100)),
        )
        assertEquals(
            ObservationState.UNAVAILABLE,
            observeOneShotRequests(installation) {
                    LifecycleProcessObservation.Present(snapshot)
                }
                .state,
        )
    }

    @Test
    fun `recording the unrelated test runner does not establish worker ownership`(@TempDir temporary: Path) {
        val installation = qualifyFixture(temporary)
        val directory = Files.createDirectories(installation.resolve("state/run/one-shot"))
        val process = ProcessHandle.current()
        Files.writeString(
            directory.resolve("foreign.json"),
            managementJson.encodeToString(
                InstalledToolInvocation(1, process.pid(), process.info().startInstant().orElseThrow().toEpochMilli())
            ),
        )
        assertEquals(ObservationState.UNAVAILABLE, observeOneShotRequests(installation).state)
    }

    @Test
    fun `unqualified marker cannot claim verified empty requests`(@TempDir temporary: Path) {
        val installation = qualifyFixture(temporary)
        Files.writeString(installation.resolve("share/kast/one-shot-observation-v1"), "foreign")
        assertEquals(ObservationState.UNAVAILABLE, observeOneShotRequests(installation).state)
    }

    private fun qualifyFixture(temporary: Path): Path {
        val installation = temporary.toRealPath()
        val marker = installation.resolve("share/kast/one-shot-observation-v1")
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "1\n")
        val jar = installation.resolve("lib/kast.jar")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "fixture-jar")
        Files.writeString(
            installation.resolve("installation.json"),
            managementJson.encodeToString(
                BundledManifest(
                    3,
                    installation.toString(),
                    listOf(
                        BundledPayload("share/kast/one-shot-observation-v1", "sha256:${sha256(marker)}", 420),
                        BundledPayload("lib/kast.jar", "sha256:${sha256(jar)}", 420),
                    ),
                )
            ),
        )
        return installation
    }
}
