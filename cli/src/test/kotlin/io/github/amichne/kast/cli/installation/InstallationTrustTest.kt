package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.cli.ide.BrokerTrustResult
import io.github.amichne.kast.cli.ide.BrokerTrustStatus
import io.github.amichne.kast.cli.ide.FilesystemBrokerTrustRegistrar
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationTrustTest {
    @Test
    fun `partial trust blocks an upgrade before changing active installation`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        val codex = Files.createDirectory(root.resolve("codex"))
        val installation = root.resolve("installation")
        val commands = root.resolve("commands")
        assertInstanceOf(
            InstallationOutcome.Complete::class.java,
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codex, "1.2.3")),
        )
        val current = Files.readSymbolicLink(installation.resolve("current"))
        val privateKey = home.resolve(".kast/approval/broker.pk8")
        val privateBytes = Files.readAllBytes(privateKey)
        Files.delete(home.resolve(".kast/approval/broker.pub"))
        assertEquals(
            InstallationOutcome.TrustRejected(io.github.amichne.kast.cli.ide.BrokerTrustFailure.INCOMPLETE_KEYS),
            executeFixtureInstallation(releaseRequest(root, installation, commands, home, codex, "1.2.4")),
        )
        assertEquals(current, Files.readSymbolicLink(installation.resolve("current")))
        assertArrayEquals(privateBytes, Files.readAllBytes(privateKey))
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(home.resolve(".kast/approval/broker.pub")))
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
                    root.resolve("installation"),
                    root.resolve("commands"),
                    home,
                    Files.createDirectory(root.resolve("codex")),
                    "1.2.3",
                    mode = InstallationMode.PLAN,
                )
            ),
        )
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(home.resolve(".kast")))
    }

    @Test
    fun `installation enrolls matching keys and repeat installation preserves exact bytes`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val home = Files.createDirectory(root.resolve("home"))
        val request =
            releaseRequest(
                root,
                root.resolve("installation"),
                root.resolve("commands"),
                home,
                Files.createDirectory(root.resolve("codex")),
                "1.2.3",
            )
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(request))
        val privateKey = home.resolve(".kast/approval/broker.pk8")
        val publicKey = home.resolve(".kast/approval/broker.pub")
        val privateBytes = Files.readAllBytes(privateKey)
        val publicBytes = Files.readAllBytes(publicKey)
        assertEquals(
            BrokerTrustResult.Complete(BrokerTrustStatus.PRESERVED),
            FilesystemBrokerTrustRegistrar(home).enroll(),
        )
        assertInstanceOf(InstallationOutcome.Complete::class.java, executeFixtureInstallation(request))
        assertArrayEquals(privateBytes, Files.readAllBytes(privateKey))
        assertArrayEquals(publicBytes, Files.readAllBytes(publicKey))
    }
}
