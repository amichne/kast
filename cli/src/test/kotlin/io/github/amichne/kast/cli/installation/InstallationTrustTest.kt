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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationTrustTest {
    @Test
    fun `successful replacement leaves an existing approval directory untouched`(@TempDir temporary: Path) {
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
        val keys = mapOf("broker.pk8" to pair.private.encoded, "broker.pub" to pair.public.encoded)
        keys.forEach { (name, bytes) ->
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
        keys.forEach { (name, bytes) -> assertTrue(bytes.contentEquals(Files.readAllBytes(approval.resolve(name)))) }
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
