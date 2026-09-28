package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Serializable private data class TestPayload(val path: String, val sha256: String, val mode: Int)

@Serializable
private data class TestManifest(
    val schemaVersion: Int,
    val installationRoot: String,
    val payloadFiles: List<TestPayload>,
    val semanticVersion: String,
)

@Serializable
private enum class TestInstallStatus {
    @SerialName("installed-activation-pending") PENDING
}

@Serializable
private enum class TestActivationType {
    @SerialName("pending") PENDING
}

@Serializable private data class TestActivation(val type: TestActivationType, val reason: String)

@Serializable
private data class TestInstallReport(
    val operation: String,
    val status: TestInstallStatus,
    val activation: TestActivation,
    val semanticVersion: String,
)

class ManagementCliTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `Clikt exposes only management commands and selects status by default`() {
        assertEquals(ManagementParsing.Selected(ManagementCommand.Status(false)), parseManagementCommand(emptyList()))
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Status(true)),
            parseManagementCommand(listOf("status", "--json")),
        )
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Connect(Harness.PI)),
            parseManagementCommand(listOf("connect", "pi")),
        )
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Connect(null)),
            parseManagementCommand(listOf("connect")),
        )
        assertTrue((parseManagementCommand(listOf("serve")) as ManagementParsing.Print).error)
        assertTrue((parseManagementCommand(listOf("call", "query_symbols")) as ManagementParsing.Print).error)
        assertTrue((parseManagementCommand(listOf("--version")) as ManagementParsing.Print).text.isNotBlank())
    }

    @Test
    fun `destination policy retains every specified branch without requiring PATH`() {
        val home = temporary.resolve("home").toString()
        val default = selectDestination(home, null, null) as DestinationSelection.Selected
        assertEquals(Path.of(home, ".local/bin/kast"), default.executable)
        assertFalse(default.onPath)
        assertFalse((selectDestination(home, "", "") as DestinationSelection.Selected).onPath)
        assertTrue((selectDestination(home, null, "$home/.local/bin:/usr/bin") as DestinationSelection.Selected).onPath)
        val xdg = temporary.resolve("config")
        assertEquals(
            xdg.resolve("kast"),
            (selectDestination(home, xdg.toString(), "/usr/bin") as DestinationSelection.Selected).executable,
        )
        assertEquals(
            DestinationSelection.Rejected(DestinationFailure.XDG_RELATIVE),
            selectDestination(home, "relative/config", "/usr/bin"),
        )
    }

    @Test
    fun `status distinguishes unavailable runtime observations from empty registrations`() {
        val root = temporary.resolve("kast")
        Files.createDirectories(root)
        val executable = temporary.resolve("kast-command")
        Files.writeString(executable, "native")
        writeManagementReceipt(
            root,
            ManagementReceipt(
                1,
                root.toString(),
                executable.toString(),
                sha256(executable),
                ReleaseChannel.STABLE,
                emptyList(),
            ),
        )
        val status = readStatus(root, executable.toString())
        assertEquals(ObservationState.VERIFIED, status.registrations.state)
        assertEquals(emptyList<ManagedRegistration>(), status.registrations.value)
        assertEquals(ObservationState.UNAVAILABLE, status.loadedVersion.state)
        assertEquals(ObservationState.UNAVAILABLE, status.activeWorkspaces.state)
        assertEquals(ObservationState.UNAVAILABLE, status.liveConnections.state)
    }

    @Test
    fun `Pi registration is idempotent and disconnect preserves a foreign replacement`() {
        val (root, home) = integrationFixture(temporary, Harness.PI)
        val target = home.resolve(".pi/agent/extensions/kast.ts")
        assertFalse(connectHarness(root, home, Harness.PI))
        assertTrue(connectHarness(root, home, Harness.PI))
        assertEquals("release adapter", Files.readString(target))
        Files.writeString(target, "foreign adapter")
        org.junit.jupiter.api.assertThrows<ManagementRejected> { disconnectHarness(root, home, Harness.PI) }
        assertEquals("foreign adapter", Files.readString(target))
        assertEquals(1, (readReceipt(root) as ReceiptRead.Read).receipt.registrations.size)
        Files.writeString(target, "release adapter")
        assertTrue(disconnectHarness(root, home, Harness.PI))
        assertFalse(Files.exists(target))
    }

    @Test
    fun `matching unowned Pi file is not adopted or removed`() {
        val (root, home) = integrationFixture(temporary, Harness.PI)
        val target = home.resolve(".pi/agent/extensions/kast.ts")
        Files.createDirectories(target.parent)
        Files.writeString(target, "release adapter")
        org.junit.jupiter.api.assertThrows<ManagementRejected> { connectHarness(root, home, Harness.PI) }
        assertEquals("release adapter", Files.readString(target))
        assertEquals(emptyList<ManagedRegistration>(), (readReceipt(root) as ReceiptRead.Read).receipt.registrations)
    }

    @Test
    fun `Copilot registration installs and removes only its bundled adapter`() {
        val (root, home) = integrationFixture(temporary, Harness.COPILOT)
        val target = home.resolve(".copilot/extensions/kast/extension.mjs")
        assertFalse(connectHarness(root, home, Harness.COPILOT))
        assertEquals("release adapter", Files.readString(target))
        assertTrue(disconnectHarness(root, home, Harness.COPILOT))
        assertFalse(Files.exists(target))
    }

    @Test
    fun `upgrade reports pending service activation after verified installation`() {
        val (root, home) = integrationFixture(temporary, Harness.PI)
        val report =
            Json.encodeToString(
                TestInstallReport(
                    "installation.install",
                    TestInstallStatus.PENDING,
                    TestActivation(TestActivationType.PENDING, "service restart required"),
                    "1.2.3",
                )
            )
        val installer = root.resolve("current/share/kast/install.sh")
        Files.createDirectories(installer.parent)
        Files.writeString(installer, "#!/bin/bash\nprintf '%s' '$report' > \"\$KAST_MANAGEMENT_REPORT_PATH\"\n")
        val failure = org.junit.jupiter.api.assertThrows<ManagementRejected> { upgradeInstallation(root, home) }
        assertEquals("service-activation", failure.stage)
        assertTrue(failure.reason.contains("installed 1.2.3"))
        assertTrue(failure.reason.contains("service restart required"))
        assertTrue(failure.reason.contains("restart IntelliJ IDEA and connected harnesses"))
        assertTrue(Files.exists(Path.of((readReceipt(root) as ReceiptRead.Read).receipt.executable)))
    }

    @Test
    fun `path preflight rejects foreign executable before installation effects`() {
        val root = temporary.resolve("kast")
        val home = temporary.resolve("home")
        Files.createDirectories(root)
        val bin = home.resolve(".local/bin")
        Files.createDirectories(bin)
        Files.writeString(bin.resolve("kast"), "foreign")
        val failure =
            org.junit.jupiter.api.assertThrows<ManagementRejected> {
                preflightPublicExecutable(root, mapOf("HOME" to home.toString(), "PATH" to bin.toString()))
            }
        assertEquals("path-preflight", failure.stage)
        assertEquals("foreign", Files.readString(bin.resolve("kast")))
    }

    @Test
    fun `native publication records selected path and retains it on upgrade`() {
        val root = temporary.resolve("install")
        val home = temporary.resolve("home")
        val configured = temporary.resolve("config")
        val bundled = root.resolve("current/share/kast/libexec/kast-management")
        Files.createDirectories(bundled.parent)
        Files.createDirectories(home)
        Files.createDirectories(configured)
        Files.writeString(bundled, "native-release-one")
        bundled.toFile().setExecutable(true)
        val first =
            commitPublicExecutable(
                root,
                mapOf(
                    "HOME" to home.toString(),
                    "XDG_CONFIG_HOME" to configured.toString(),
                    "PATH" to "/usr/bin",
                ),
                ReleaseChannel.STABLE,
            )
        assertEquals(configured.resolve("kast"), first.path)
        assertFalse(first.onPath)
        assertEquals("native-release-one", Files.readString(first.path))
        Files.writeString(bundled, "native-release-two")
        val next =
            commitPublicExecutable(
                root,
                mapOf(
                    "HOME" to home.toString(),
                    "XDG_CONFIG_HOME" to "relative/changed-setting",
                    "PATH" to "/usr/bin",
                ),
                ReleaseChannel.STABLE,
            )
        assertEquals(first.path, next.path)
        assertEquals("native-release-two", Files.readString(first.path))
        assertEquals(first.path.toString(), (readReceipt(root) as ReceiptRead.Read).receipt.executable)
    }
}

internal fun integrationFixture(temporary: Path, harness: Harness): Pair<Path, Path> {
    val root = temporary.resolve("kast")
    val home = temporary.resolve("home")
    val version = root.resolve("versions/1.2.3-deadbeef")
    val relative =
        when (harness) {
            Harness.COPILOT -> "share/kast/adapters/copilot/extension.mjs"
            Harness.PI -> "share/kast/adapters/pi/extension.ts"
            Harness.CODEX -> "bin/kast-mcp-complete"
        }
    val source = version.resolve(relative)
    Files.createDirectories(source.parent)
    Files.createDirectories(home)
    Files.writeString(source, "release adapter")
    Files.writeString(
        version.resolve("installation.json"),
        Json.encodeToString(
            TestManifest(
                2,
                version.toRealPath().toString(),
                listOf(
                    TestPayload(
                        relative,
                        "sha256:${sha256(source)}",
                        420,
                    )
                ),
                "1.2.3",
            )
        ),
    )
    Files.createSymbolicLink(root.resolve("current"), root.relativize(version))
    val executable = temporary.resolve("kast-command")
    Files.writeString(executable, "native")
    writeManagementReceipt(
        root,
        ManagementReceipt(
            1,
            root.toString(),
            executable.toString(),
            sha256(executable),
            ReleaseChannel.STABLE,
            emptyList(),
        ),
    )
    return root to home
}
