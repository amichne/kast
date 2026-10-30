package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OneShotObservationTest {
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
