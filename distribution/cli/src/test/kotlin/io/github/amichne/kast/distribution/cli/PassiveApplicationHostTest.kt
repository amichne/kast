package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PassiveApplicationHostTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `passive application identity works without a project or coordinator`() {
        val fixture = fixture()
        val before = Files.readAllBytes(fixture.descriptor)
        val status =
            readApplicationHost(fixture.root, fixture.home) { pid ->
                assertEquals(42, pid)
                fixture.process
            }
        assertEquals(Observation.verified(fixture.loaded), status)
        assertEquals("9.8.7", status.value?.runtimeIdentity?.buildIdentity?.version)
        assertTrue(before.contentEquals(Files.readAllBytes(fixture.descriptor)))
        assertTrue(Files.notExists(fixture.root.resolve("installation/state/epoch.json")))
    }

    @Test
    fun `a stale PID start or foreign launcher cannot qualify loaded identity`() {
        val fixture = fixture()
        for (process in
            listOf(
                fixture.process.copy(processStartEpochMillis = 999),
                fixture.process.copy(executable = "/foreign/idea"),
            )) {
            val status = readApplicationHost(fixture.root, fixture.home) { process }
            assertEquals(ObservationState.UNAVAILABLE, status.state)
            assertEquals("PROCESS_IDENTITY_MISMATCH", status.reason)
        }
    }

    @Test
    fun `a symlink advertisement cannot redirect passive reads`() {
        val fixture = fixture()
        val protected = temporary.resolve("protected.json")
        Files.writeString(protected, "preserve this file")
        Files.delete(fixture.descriptor)
        Files.createSymbolicLink(fixture.descriptor, protected)
        val status =
            readApplicationHost(fixture.root, fixture.home) { error("symlink rejection precedes process inspection") }
        assertEquals(ObservationState.UNAVAILABLE, status.state)
        assertEquals("preserve this file", Files.readString(protected))
    }

    @Test
    fun `a different profile or lifetime rejects before process inspection`() {
        val fixture = fixture()
        val original = managementJson.decodeFromString<Advertisement>(Files.readString(fixture.descriptor))
        for (document in
            listOf(
                original.copy(host = UUID(0, 99).toString()),
                original.copy(
                    application =
                        fixture.loaded.copy(
                            runtimeIdentity = fixture.loaded.runtimeIdentity.copy(profile = "/foreign/profile")
                        )
                ),
            )) {
            Files.writeString(fixture.descriptor, managementJson.encodeToString(document))
            val status =
                readApplicationHost(fixture.root, fixture.home) {
                    error("identity mismatch precedes process inspection")
                }
            assertEquals(ObservationState.UNAVAILABLE, status.state)
            assertEquals("DESCRIPTOR_REJECTED", status.reason)
        }
    }

    private fun fixture(): Fixture {
        val fixture = createLifecycleFixture(temporary)
        val idea = temporary.toRealPath().resolve("IDE.app/Contents")
        Files.writeString(
            idea.resolve("Resources/product-info.json"),
            managementJson.encodeToString(
                Product("262.1.1", listOf(Launch("macOS", "aarch64", "../MacOS/idea")), "IntelliJIdea2026.2")
            ),
        )
        val profile =
            Files.createDirectories(fixture.home.resolve("Library/Application Support/JetBrains/IntelliJIdea2026.2"))
        val host = UUID(0, 1).toString()
        val loaded = loadedHost(idea, profile, host)
        val hash =
            MessageDigest.getInstance("SHA-256").digest(idea.toString().toByteArray()).take(16).joinToString("") {
                "%02x".format(it)
            }
        val directory = Files.createDirectories(fixture.home.resolve(".kast/ide-lifecycle/$hash"))
        val descriptor = directory.resolve("endpoint.json")
        Files.writeString(
            descriptor,
            managementJson.encodeToString(
                Advertisement(
                    "KAST_IDE_ENDPOINT",
                    3,
                    idea.toString(),
                    directory.resolve("host.sock").toString(),
                    42,
                    host,
                    "kast.workspace.lifecycle.v2",
                    listOf("WORKSPACE_LIFECYCLE"),
                    loaded,
                )
            ),
        )
        val process = ApplicationProcessObservation.Present(42, 100, idea.resolve("MacOS/idea").toString())
        return Fixture(fixture.root, fixture.home, descriptor, loaded, process)
    }

    private fun loadedHost(idea: Path, profile: Path, host: String): LoadedApplicationHost =
        LoadedApplicationHost(
            ApplicationRuntimeIdentity(
                idea.toString(),
                profile.toString(),
                42,
                100,
                host,
                ApplicationHostBuild("9.8.7", "a".repeat(40), "b".repeat(40)),
                ApplicationAutoReload.ENABLED,
            ),
            ApplicationHostCompatibility(
                "262.1.1",
                "262.1.1-IJ",
                "9.8.7",
                ApplicationHostedContract(
                    "HOSTED_CONTRACT",
                    "kast.ide-hosted.runtime.v1",
                    "sha256:" + "c".repeat(64),
                    "sha256:" + "d".repeat(64),
                    listOf("query.run"),
                ),
            ),
        )

    private data class Fixture(
        val root: Path,
        val home: Path,
        val descriptor: Path,
        val loaded: LoadedApplicationHost,
        val process: ApplicationProcessObservation.Present,
    )

    @Serializable
    private data class Product(val buildNumber: String, val launch: List<Launch>, val dataDirectoryName: String)

    @Serializable private data class Launch(val os: String, val arch: String, val launcherPath: String)

    @Serializable
    private data class Advertisement(
        val type: String,
        val protocol: Int,
        val root: String,
        val socket: String,
        val hostPid: Long,
        val host: String,
        val querySchema: String,
        val operations: List<String>,
        val application: LoadedApplicationHost,
    )
}
