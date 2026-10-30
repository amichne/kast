package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeQueryLifecycleTest {
    @Test
    fun `native worker requires exact executable and admitted payload`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val owned = assertInstanceOf(ShutdownProcessAdmission.Owned::class.java, fixture.admit())
        assertEquals(fixture.snapshot, owned.process.snapshot)
        assertEquals(owned, owned.process.revalidate(LifecycleProcessObservation.Present(fixture.snapshot)))
        assertEquals(
            ShutdownProcessAdmission.Rejected,
            OwnedLifecycleProcess.admit(fixture.record, fixture.observation(), fixture.installation),
        )
        for (command in
            listOf("kast-query", "/foreign/bin/kast-query", "${fixture.installation}/bin/../bin/kast-query")) {
            assertEquals(ShutdownProcessAdmission.Rejected, fixture.admit(fixture.snapshot.copy(command = command)))
        }
        for (manifest in
            listOf(
                fixture.manifest.copy(schemaVersion = 99),
                fixture.manifest.copy(installationRoot = temporary.resolve("foreign").toString()),
                fixture.manifest.copy(payloadFiles = emptyList()),
                fixture.manifest.copy(
                    payloadFiles = fixture.manifest.payloadFiles + fixture.manifest.payloadFiles.first()
                ),
                fixture.manifest.copy(
                    payloadFiles = fixture.manifest.payloadFiles.map { it.copy(sha256 = "sha256:${"0".repeat(64)}") }
                ),
            )) {
            assertEquals(ShutdownProcessAdmission.Rejected, fixture.admit(manifest = manifest))
        }
    }

    @Test
    fun `PID reuse is finished and missing or mismatched identity rejects`() {
        val installation = Path.of("/selected/installation")
        val executable = installation.resolve("bin/kast-query")
        val fixture =
            NativeFixture(
                installation,
                executable,
                BundledManifest(3, installation.toString(), emptyList()),
                InstalledToolInvocation(1, 42, 100),
                LifecycleProcessSnapshot(42, 100, executable.toString(), listOf("query")),
            )
        assertEquals(ShutdownProcessAdmission.Finished, fixture.admit(fixture.snapshot.copy(startEpochMillis = 101)))
        assertEquals(ShutdownProcessAdmission.Rejected, fixture.admit(fixture.snapshot.copy(pid = 43)))
        assertEquals(
            ShutdownProcessAdmission.Rejected,
            OwnedLifecycleProcess.admit(
                fixture.record,
                LifecycleProcessObservation.Unavailable,
                fixture.installation,
                fixture.manifest,
            ),
        )
        assertEquals(
            ShutdownProcessAdmission.Finished,
            OwnedLifecycleProcess.admit(
                fixture.record,
                LifecycleProcessObservation.Absent,
                fixture.installation,
                fixture.manifest,
            ),
        )
        for (record in
            listOf(
                fixture.record.copy(schemaVersion = 99),
                fixture.record.copy(pid = 0),
                fixture.record.copy(startEpochMillis = 0),
            )) {
            assertEquals(
                ShutdownProcessAdmission.Rejected,
                OwnedLifecycleProcess.admit(record, fixture.observation(), fixture.installation, fixture.manifest),
            )
        }
    }

    @Test
    fun `same bytes at a replacement inode cannot retain retirement authority`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val owned = assertInstanceOf(ShutdownProcessAdmission.Owned::class.java, fixture.admit()).process
        val replacement = temporary.resolve("replacement")
        Files.copy(fixture.executable, replacement)
        assertTrue(replacement.toFile().setExecutable(true))
        Files.move(replacement, fixture.executable, REPLACE_EXISTING)
        assertEquals(ShutdownProcessAdmission.Rejected, owned.revalidate(fixture.observation()))
        assertEquals(
            ShutdownProcessAdmission.Finished,
            owned.revalidate(LifecycleProcessObservation.Present(fixture.snapshot.copy(startEpochMillis = 101))),
        )
    }

    @Test
    fun `in-place content change cannot retain authority even with restored timestamp`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val owned = assertInstanceOf(ShutdownProcessAdmission.Owned::class.java, fixture.admit()).process
        val modified = Files.getLastModifiedTime(fixture.executable)
        val size = Files.size(fixture.executable)
        Files.writeString(fixture.executable, "changed-native-bytes")
        Files.setLastModifiedTime(fixture.executable, modified)
        assertEquals(size, Files.size(fixture.executable))
        assertEquals(ShutdownProcessAdmission.Rejected, owned.revalidate(fixture.observation()))
        assertEquals(ShutdownProcessAdmission.Rejected, fixture.admit())
    }

    @Test
    fun `symlink or non-executable payload rejects`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        assertTrue(fixture.executable.toFile().setExecutable(false, false))
        assertEquals(ShutdownProcessAdmission.Rejected, fixture.admit())
        val target = temporary.resolve("foreign-query")
        Files.move(fixture.executable, target)
        assertTrue(target.toFile().setExecutable(true))
        Files.createSymbolicLink(fixture.executable, target)
        assertEquals(ShutdownProcessAdmission.Rejected, fixture.admit())
    }

    @Test
    fun `JVM MCP RPC and public worker admission remains manifest qualified`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val jar = fixture.installation.resolve("lib/kast.jar")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "fixture-jar")
        val manifest =
            fixture.manifest.copy(
                payloadFiles =
                    fixture.manifest.payloadFiles + BundledPayload("lib/kast.jar", "sha256:${sha256(jar)}", 420)
            )
        for (main in listOf("mcp.KastMcpMain", "rpc.KastToolRpcMain", "publicshell.KastPublicCliMain")) {
            val snapshot =
                fixture.snapshot.copy(
                    command = "/fixture/bin/java",
                    arguments = listOf("-cp", jar.toString(), "io.github.amichne.kast.cli.$main"),
                )
            assertInstanceOf(ShutdownProcessAdmission.Owned::class.java, fixture.admit(snapshot, manifest))
            assertEquals(ShutdownProcessAdmission.Rejected, fixture.admit(snapshot))
        }
    }

    @Test
    fun `request retirement accepts native records and verifies the exact incarnation has exited`(
        @TempDir temporary: Path
    ) {
        val fixture = fixture(temporary)
        val directory = Files.createDirectories(fixture.installation.resolve("state/run/one-shot"))
        val entry = directory.resolve("request.json")
        val recordBytes = managementJson.encodeToString(fixture.record)
        Files.writeString(entry, recordBytes)
        var observations = 0
        var retirements = 0
        val processes =
            object : LifecycleProcesses {
                override fun observe(pid: Long): LifecycleProcessObservation {
                    assertEquals(fixture.record.pid, pid)
                    observations++
                    check(observations <= 2) { "unexpected process observation" }
                    return if (observations == 1) fixture.observation() else LifecycleProcessObservation.Absent
                }

                override fun retire(
                    process: OwnedLifecycleProcess,
                    operation: LifecycleOperation,
                    allowance: Duration,
                ): LifecycleEffect {
                    retirements++
                    assertEquals(1, retirements)
                    assertEquals(fixture.snapshot, process.snapshot)
                    assertEquals(LifecycleOperation.STOP_FORCE, operation)
                    assertTrue(allowance > Duration.ZERO)
                    assertInstanceOf(
                        ShutdownProcessAdmission.Owned::class.java,
                        process.revalidate(fixture.observation()),
                    )
                    return LifecycleEffect.Completed
                }

                override fun selectedHost(executable: Path): LifecycleEffect = error("unexpected host observation")
            }
        assertEquals(
            LifecycleEffect.Completed,
            InstallationRequestRetirement(fixture.installation, fixture.manifest, processes)
                .retire(LifecycleOperation.STOP_FORCE),
        )
        assertEquals(2, observations)
        assertEquals(1, retirements)
        assertEquals(recordBytes, Files.readString(entry))
    }

    @Test
    fun `foreign native record rejects retirement without granting effects`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val directory = Files.createDirectories(fixture.installation.resolve("state/run/tool-sessions"))
        Files.writeString(directory.resolve("request.json"), managementJson.encodeToString(fixture.record))
        var observations = 0
        val processes =
            object : LifecycleProcesses {
                override fun observe(pid: Long): LifecycleProcessObservation {
                    assertEquals(fixture.record.pid, pid)
                    observations++
                    assertEquals(1, observations)
                    return LifecycleProcessObservation.Present(
                        fixture.snapshot.copy(command = "/foreign/bin/kast-query")
                    )
                }

                override fun retire(
                    process: OwnedLifecycleProcess,
                    operation: LifecycleOperation,
                    allowance: Duration,
                ): LifecycleEffect = error("unexpected retirement")

                override fun selectedHost(executable: Path): LifecycleEffect = error("unexpected host observation")
            }
        assertEquals(
            LifecycleEffect.Rejected(LifecycleFailure.REQUEST_OWNERSHIP_UNPROVEN),
            InstallationRequestRetirement(fixture.installation, fixture.manifest, processes)
                .retire(LifecycleOperation.STOP_FORCE),
        )
        assertEquals(1, observations)
        assertTrue(Files.exists(fixture.executable))
    }

    @Test
    fun `status counts only the same manifest qualified native ownership`(@TempDir temporary: Path) {
        val fixture = fixture(temporary)
        val directory = fixture.installation.resolve("state/run/one-shot")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("request.json"), managementJson.encodeToString(fixture.record))
        var observations = 0
        assertEquals(
            Observation.verified(1),
            observeOneShotRequests(fixture.installation) { pid ->
                assertEquals(fixture.record.pid, pid)
                observations++
                fixture.observation()
            },
        )
        assertEquals(1, observations)
        assertEquals(
            ObservationState.UNAVAILABLE,
            observeOneShotRequests(fixture.installation) {
                    LifecycleProcessObservation.Present(fixture.snapshot.copy(command = "/foreign/bin/kast-query"))
                }
                .state,
        )
        assertEquals(
            Observation.verified(0),
            observeOneShotRequests(fixture.installation) {
                LifecycleProcessObservation.Present(fixture.snapshot.copy(startEpochMillis = 101))
            },
        )
        Files.writeString(fixture.executable, "foreign")
        assertEquals(
            ObservationState.UNAVAILABLE,
            observeOneShotRequests(fixture.installation) { fixture.observation() }.state,
        )
    }

    private fun fixture(temporary: Path): NativeFixture {
        val installation = temporary.toRealPath().resolve("installation with spaces")
        val executable = installation.resolve("bin/kast-query")
        Files.createDirectories(executable.parent)
        Files.writeString(executable, "native-fixture-bytes")
        assertTrue(executable.toFile().setExecutable(true))
        val marker = installation.resolve("share/kast/one-shot-observation-v1")
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "1\n")
        val manifest =
            BundledManifest(
                3,
                installation.toString(),
                listOf(
                    BundledPayload("bin/kast-query", "sha256:${sha256(executable)}", 493),
                    BundledPayload("share/kast/one-shot-observation-v1", "sha256:${sha256(marker)}", 420),
                ),
            )
        Files.writeString(installation.resolve("installation.json"), managementJson.encodeToString(manifest))
        return NativeFixture(
            installation,
            executable,
            manifest,
            InstalledToolInvocation(1, 42, 100),
            LifecycleProcessSnapshot(42, 100, executable.toString(), listOf("query")),
        )
    }

    private data class NativeFixture(
        val installation: Path,
        val executable: Path,
        val manifest: BundledManifest,
        val record: InstalledToolInvocation,
        val snapshot: LifecycleProcessSnapshot,
    ) {
        fun observation() = LifecycleProcessObservation.Present(snapshot)

        fun admit(snapshot: LifecycleProcessSnapshot = this.snapshot, manifest: BundledManifest = this.manifest) =
            OwnedLifecycleProcess.admit(record, LifecycleProcessObservation.Present(snapshot), installation, manifest)
    }
}
