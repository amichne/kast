package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class InstallationRecoveryRemovalTest {
    @Test
    fun `preexisting recovery siblings reject admission before retirement`(@TempDir temporary: Path) {
        val root = installation(temporary)
        val sibling = Files.createDirectory(root.parent.resolve("recovery/foreign"))
        val sentinel = Files.writeString(sibling.resolve("retain"), "foreign recovery")
        assertEquals(Refinement.Rejected(RecoveryRemovalFailure.FOREIGN_CONTENT), admitRecoveryRemoval(root))
        assertEquals("foreign recovery", Files.readString(sentinel))
        assertTrue(Files.exists(root))
        assertTrue(Files.exists(root.parent.resolve("recovery/installation/receipt.json")))
    }

    @Test
    fun `captured private empty recovery parent removes only after retirement`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("installation"))
        val directory =
            Files.createDirectory(
                root.parent.resolve("recovery"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        val proof = refined(admitRecoveryRemoval(root))
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.INSTALLATION_NOT_RETIRED),
            removeRetiredRecovery(root, proof),
        )
        Files.delete(root)
        assertEquals(RecoveryRemovalOutcome.Removed, removeRetiredRecovery(root, proof))
        assertFalse(Files.exists(directory))
        assertEquals(RecoveryRemovalOutcome.Removed, removeRetiredRecovery(root, proof))
    }

    @Test
    fun `empty recovery proof protects later content and changed directory identity`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("installation"))
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val directory = Files.createDirectory(root.parent.resolve("recovery"), permissions)
        val proof = refined(admitRecoveryRemoval(root))
        val foreign = Files.writeString(directory.resolve("foreign"), "retain")
        assertEquals(Refinement.Rejected(RecoveryRemovalFailure.FOREIGN_CONTENT), admitRecoveryRemoval(root))
        Files.delete(root)
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.FOREIGN_CONTENT),
            removeRetiredRecovery(root, proof),
        )
        assertEquals("retain", Files.readString(foreign))
        Files.delete(foreign)
        Files.move(directory, directory.resolveSibling("retained-recovery"))
        Files.createDirectory(directory, permissions)
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN),
            removeRetiredRecovery(root, proof),
        )
        assertTrue(Files.exists(directory))
    }

    @Test
    fun `terminal owned bundle removes after payload retirement and retry is idempotent`(@TempDir temporary: Path) {
        val root = installation(temporary)
        val proof = refined(admitRecoveryRemoval(root))
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.INSTALLATION_NOT_RETIRED),
            removeRetiredRecovery(root, proof),
        )
        retire(root)
        assertEquals(RecoveryRemovalOutcome.Removed, removeRetiredRecovery(root, proof))
        assertFalse(Files.exists(root.parent.resolve("recovery")))
        assertEquals(RecoveryRemovalOutcome.Removed, removeRetiredRecovery(root, proof))
    }

    @Test
    fun `partial cleanup resumes from captured inventory`(@TempDir temporary: Path) {
        val root = installation(temporary)
        val proof = refined(admitRecoveryRemoval(root))
        retire(root)
        Files.delete(root.parent.resolve("recovery/installation/installation-recovery.py"))
        assertEquals(RecoveryRemovalOutcome.Removed, removeRetiredRecovery(root, proof))
        assertFalse(Files.exists(root.parent.resolve("recovery/installation")))
    }

    @Test
    fun `late recovery sibling rejects before removing captured bundle files`(@TempDir temporary: Path) {
        val root = installation(temporary)
        val proof = refined(admitRecoveryRemoval(root))
        val sibling = Files.createDirectory(root.parent.resolve("recovery/other"))
        val sentinel = Files.writeString(sibling.resolve("sentinel"), "unrelated")
        retire(root)
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.FOREIGN_CONTENT),
            removeRetiredRecovery(root, proof),
        )
        assertEquals("unrelated", Files.readString(sentinel))
        assertTrue(Files.exists(root.parent.resolve("recovery/installation/receipt.json")))
        assertTrue(Files.exists(root.parent.resolve("recovery/installation/installation-recovery.py")))
    }

    @Test
    fun `changed captured file rejects before removing any protected file`(@TempDir temporary: Path) {
        val root = installation(temporary)
        val proof = refined(admitRecoveryRemoval(root))
        retire(root)
        val bundle = root.parent.resolve("recovery/installation")
        val first = bundle.resolve("installation-recovery.py")
        val receipt = Files.readString(bundle.resolve("receipt.json"))
        Files.writeString(bundle.resolve("installation-lifecycle.py"), "replacement")
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN),
            removeRetiredRecovery(root, proof),
        )
        assertTrue(Files.exists(first))
        assertEquals(receipt, Files.readString(bundle.resolve("receipt.json")))
    }

    @Test
    fun `foreign file and unproven late bundle remain untouched`(@TempDir temporary: Path) {
        val root = installation(temporary)
        val proof = refined(admitRecoveryRemoval(root))
        val bundle = root.parent.resolve("recovery/installation")
        val foreign = Files.writeString(bundle.resolve("foreign"), "unrelated")
        assertEquals(Refinement.Rejected(RecoveryRemovalFailure.FOREIGN_CONTENT), admitRecoveryRemoval(root))
        retire(root)
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.FOREIGN_CONTENT),
            removeRetiredRecovery(root, proof),
        )
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN),
            removeRetiredRecovery(root, ManagedRecoveryRemovalProof.Absent),
        )
        assertEquals("unrelated", Files.readString(foreign))
    }

    @Test
    fun `pending unresolved and invalid utf8 receipts cannot grant removal`(@TempDir temporary: Path) {
        val root = installation(temporary)
        val path = root.parent.resolve("recovery/installation/receipt.json")
        val original = Files.readString(path)
        for ((stage, failure) in
            listOf(
                "PluginPrepared" to RecoveryRemovalFailure.RECOVERY_PENDING,
                "UpgradeFinalizing" to RecoveryRemovalFailure.RECOVERY_PENDING,
                "DetachedWithUnresolvedState" to RecoveryRemovalFailure.RECOVERY_UNRESOLVED,
                "CleanBaselineRestored" to RecoveryRemovalFailure.RECOVERY_UNRESOLVED,
            )) {
            Files.writeString(path, original.replace("Prepared", stage))
            assertEquals(Refinement.Rejected(failure), admitRecoveryRemoval(root))
        }
        Files.write(path, byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(Refinement.Rejected(RecoveryRemovalFailure.IO_UNAVAILABLE), admitRecoveryRemoval(root))
        assertEquals(listOf(0xc3.toByte(), 0x28.toByte()), Files.readAllBytes(path).toList())
    }

    private fun installation(temporary: Path): Path {
        val root = Files.createDirectory(temporary.toRealPath().resolve("installation"))
        val scripts = Files.createDirectories(root.resolve("share/kast"))
        Files.writeString(scripts.resolve("installation-recovery.py"), "fixture recovery")
        Files.writeString(scripts.resolve("installation-lifecycle.py"), "fixture lifecycle")
        assertEquals(InstallationRecoveryPreparation.Prepared, prepareInstallationRecovery(root))
        return root
    }

    private fun retire(root: Path) {
        Files.delete(root.resolve("share/kast/installation-recovery.py"))
        Files.delete(root.resolve("share/kast/installation-lifecycle.py"))
        Files.delete(root.resolve("share/kast"))
        Files.delete(root.resolve("share"))
        Files.delete(root)
    }

    private fun <T, F> refined(value: Refinement<T, F>): T = assertInstanceOf<Refinement.Refined<T>>(value).value
}
