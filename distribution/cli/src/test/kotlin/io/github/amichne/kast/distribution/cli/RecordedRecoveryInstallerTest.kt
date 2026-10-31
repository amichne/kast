package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationReplacementReceipt
import io.github.amichne.kast.distribution.contract.InstallationReplacementStage
import io.github.amichne.kast.distribution.contract.ManagedControlReservation
import io.github.amichne.kast.distribution.contract.PreviousInstallationPayload
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RecordedRecoveryInstallerTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `recorded staged installer remains reachable without an active installation`() {
        val fixture = fixture()
        val before = Files.readAllBytes(fixture.receipt)
        var calls = 0
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(json = true, restartIde = true, ideaHome = "ignored"),
                mapOf("EXAMPLE" to "kept"),
                ManagedUpgradeExecution(
                    readRelease = { error("release access during recorded recovery") },
                    installer = { argv, directory, environment ->
                        calls++
                        assertEquals(
                            listOf(
                                "/bin/bash",
                                fixture.staged.resolve("share/kast/install-candidate.sh").toString(),
                                "--recover-only",
                                "--install-root",
                                fixture.root.toString(),
                                "--json",
                            ),
                            argv,
                        )
                        assertEquals(fixture.staged, directory)
                        assertEquals(mapOf("EXAMPLE" to "kept", "HOME" to fixture.home.toString()), environment)
                        130
                    },
                ),
            )
        assertEquals(ManagedUpgradeOutcome.ChildExited(130), result)
        assertEquals(1, calls)
        assertEquals(before.toList(), Files.readAllBytes(fixture.receipt).toList())
        assertFalse(Files.exists(fixture.root.resolve("installation")))
    }

    @Test
    fun `changed helper bytes reject before a recovery child`() {
        val fixture = fixture()
        Files.writeString(fixture.staged.resolve("lib/kast.jar"), "changed bytes")
        assertRejected(fixture)
    }

    @Test
    fun `unmanifested helper files cannot enter the recovery interpreter`() {
        val fixture = fixture()
        Files.writeString(fixture.staged.resolve("share/kast/foreign.py"), "preserve")
        assertRejected(fixture)
    }

    @Test
    fun `a stage alias cannot borrow the recorded directory identity`() {
        val fixture = fixture()
        val retained = fixture.root.resolve("retained")
        Files.move(fixture.staged, retained)
        Files.createSymbolicLink(fixture.staged, retained)
        assertRejected(fixture)
        assertEquals("payload", Files.readString(retained.resolve("lib/kast.jar")))
    }

    @Test
    fun `a mismatched manifest digest cannot launch a candidate`() {
        val fixture = fixture()
        Files.writeString(
            fixture.receipt,
            managementJson.encodeToString(
                fixture.record.copy(reservation = fixture.reservation.copy(manifestSha256 = "c".repeat(64)))
            ),
        )
        assertRejected(fixture)
    }

    @Test
    fun `changed payload permissions reject before a recovery child`() {
        val fixture = fixture()
        Files.setPosixFilePermissions(
            fixture.staged.resolve("lib/kast.jar"),
            PosixFilePermissions.fromString("rwxr-xr-x"),
        )
        assertRejected(fixture)
    }

    @Test
    fun `the receipt cannot name a different candidate incarnation`() {
        val fixture = fixture()
        val identity = fixture.record.installationIdentity
        Files.writeString(
            fixture.receipt,
            managementJson.encodeToString(
                fixture.record.copy(installationIdentity = identity.copy(inode = identity.inode + 1))
            ),
        )
        assertRejected(fixture)
    }

    @Test
    fun `an uncertain installed recovery child never triggers a recorded retry`() {
        val fixture = fixture()
        Files.move(fixture.staged, fixture.root.resolve("installation"))
        var calls = 0
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(),
                emptyMap(),
                ManagedUpgradeExecution(
                    readRelease = { error("release read after uncertain recovery") },
                    installer = { _, _, _ ->
                        calls++
                        throw IOException("uncertain recovery child")
                    },
                ),
            )
        assertEquals(
            ManagedUpgradeOutcome.RecoveryRequired(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED),
            result,
        )
        assertEquals(1, calls)
    }

    @Test
    fun `a recorded legacy version directory cannot become a recovery stage`() {
        val fixture = fixture()
        val versions = Files.createDirectory(fixture.root.resolve("versions"))
        val legacy = versions.resolve("1.2.3")
        Files.move(fixture.staged, legacy)
        Files.writeString(
            fixture.receipt,
            managementJson.encodeToString(
                fixture.record.copy(reservation = fixture.reservation.copy(staged = legacy.toString()))
            ),
        )
        assertRejected(fixture)
        assertEquals("payload", Files.readString(legacy.resolve("lib/kast.jar")))
    }

    private fun assertRejected(fixture: Fixture) {
        val before = Files.readAllBytes(fixture.receipt)
        val result =
            executeManagedUpgrade(
                fixture.root,
                fixture.home,
                ManagementCommand.Upgrade(),
                emptyMap(),
                ManagedUpgradeExecution(
                    readRelease = { error("release access during rejected recovery") },
                    installer = { _, _, _ -> error("unadmitted recovery child") },
                ),
            )
        assertEquals(ManagedUpgradeOutcome.RecoveryRequired(BuildSelectionFailure.ARTIFACT_INVALID), result)
        assertEquals(before.toList(), Files.readAllBytes(fixture.receipt).toList())
    }

    private fun fixture(): Fixture {
        val root = Files.createDirectory(temporary.toRealPath().resolve("kast"))
        val home = Files.createDirectory(temporary.toRealPath().resolve("home"))
        val staged = Files.createDirectory(root.resolve(".install-selected"))
        val files =
            listOf(
                file(staged, "bin/kast-complete", "payload", "rwxr-xr-x", 493),
                file(staged, "lib/kast.jar", "payload", "rw-r--r--", 420),
                file(staged, "share/kast/install-candidate.sh", "#!/bin/bash\nexit 130\n", "rwxr-xr-x", 493),
                file(staged, "share/kast/managed-install-v1", "1\n", "rw-r--r--", 420),
            )
        val manifest = staged.resolve("installation.json")
        Files.writeString(
            manifest,
            managementJson.encodeToString(
                RecoveryManifestFixture(
                    3,
                    "1.2.3",
                    root.resolve("installation").toString(),
                    "sha256:" + "b".repeat(64),
                    files,
                )
            ),
        )
        val slot = Files.createDirectories(root.resolve("recovery/replacement"))
        val reservation =
            ManagedControlReservation(
                "12345678-1234-1234-1234-123456789abc",
                staged.toString(),
                "b".repeat(64),
                "1.2.3",
                sha256(manifest),
            )
        val record =
            InstallationReplacementReceipt(
                stage = InstallationReplacementStage.PREPARED,
                installation = root.resolve("installation").toString(),
                installationIdentity = uninstallFilesystemIdentity(staged),
                previous = PreviousInstallationPayload.None,
                reservation = reservation,
            )
        val receipt = slot.resolve("receipt.json")
        Files.writeString(receipt, managementJson.encodeToString(record))
        Files.setPosixFilePermissions(receipt, PosixFilePermissions.fromString("rw-------"))
        return Fixture(root, home, staged, receipt, record, reservation)
    }

    private fun file(root: Path, relative: String, bytes: String, permissions: String, mode: Int): BundledPayload {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, bytes)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        return BundledPayload(relative, "sha256:${sha256(path)}", mode)
    }

    private data class Fixture(
        val root: Path,
        val home: Path,
        val staged: Path,
        val receipt: Path,
        val record: InstallationReplacementReceipt,
        val reservation: ManagedControlReservation,
    )

    @Serializable
    private data class RecoveryManifestFixture(
        val schemaVersion: Int,
        val semanticVersion: String,
        val installationRoot: String,
        val controlSha256: String,
        val payloadFiles: List<BundledPayload>,
    )
}
