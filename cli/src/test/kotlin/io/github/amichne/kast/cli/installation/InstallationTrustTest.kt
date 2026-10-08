package io.github.amichne.kast.cli.installation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationTrustTest {
    @Test
    fun `successful replacement retires legacy approval keys without creating new authority`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        val codex = Files.createDirectory(root.resolve("codex"))
        val installation = home.resolve(".local/share/kast")
        val commands = home.resolve(".local/bin")
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codex, "1.2.3")),
        )
        discardFixtureReplacementAfterSetup(installation)
        Files.writeString(
            installation.resolve("installation/config/workspaces.json"),
            Json.encodeToString(EmptyRegistryFixture(2, 1, listOf(root.toString()))),
        )
        val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        Files.createDirectory(home.resolve(".kast"), mode)
        val approval = Files.createDirectory(home.resolve(".kast/approval"), mode)
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        listOf("broker.pk8" to pair.private.encoded, "broker.pub" to pair.public.encoded).forEach { (name, bytes) ->
            val file =
                Files.createFile(
                    approval.resolve(name),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            Files.write(file, bytes)
        }
        val completed = executeFixtureInstallation(releaseRequest(root, installation, commands, home, codex, "1.2.4"))
        assertInstanceOf(InstallationOutcome.Complete::class.java, completed, completed.toString())
        assertEquals("1.2.4", (completed as InstallationOutcome.Complete).report.semanticVersion)
        assertFalse(Files.exists(approval))
    }

    @Serializable
    private data class EmptyRegistryFixture(val schemaVersion: Int, val revision: Int, val roots: List<String>)

    @Test
    fun `fresh installation creates no approval directory`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(
                releaseRequest(
                    root,
                    home.resolve(".local/share/kast"),
                    home.resolve(".local/bin"),
                    home,
                    Files.createDirectory(root.resolve("codex")),
                    "1.2.3",
                )
            ),
        )
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(home.resolve(".kast/approval")))
    }

    @Test
    fun `malformed legacy key retains the committed installation and exact retirement failure`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        Files.createDirectory(home.resolve(".kast"), privateDirectory)
        val approval = Files.createDirectory(home.resolve(".kast/approval"), privateDirectory)
        val key =
            Files.createFile(
                approval.resolve("broker.pk8"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        Files.writeString(key, "malformed legacy material")
        val outcome =
            executeFixtureInstallation(
                releaseRequest(
                    root,
                    home.resolve(".local/share/kast"),
                    home.resolve(".local/bin"),
                    home,
                    Files.createDirectory(root.resolve("codex")),
                    "1.2.3",
                )
            )
        val retained = assertInstanceOf(InstallationOutcome.LegacyApprovalRetained::class.java, outcome)
        assertEquals(io.github.amichne.kast.cli.ide.RetiredApprovalFailure.INVALID_KEY, retained.failure)
        assertEquals("1.2.3", retained.report.semanticVersion)
        org.junit.jupiter.api.Assertions.assertTrue(
            Files.exists(home.resolve(".local/share/kast/installation/bin/kast"))
        )
        assertEquals("malformed legacy material", Files.readString(key))
    }

    @Test
    fun `installation plan creates no trust state`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(
                releaseRequest(
                    root,
                    root.resolve("home/.local/share/kast"),
                    root.resolve("home/.local/bin"),
                    home,
                    Files.createDirectory(root.resolve("codex")),
                    "1.2.3",
                    mode = InstallationMode.PLAN,
                )
            ),
        )
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(home.resolve(".kast")))
    }
}
