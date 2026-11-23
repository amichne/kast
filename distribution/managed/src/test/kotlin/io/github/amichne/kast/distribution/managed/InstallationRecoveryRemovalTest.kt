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
    fun `absent recovery directory admits removal without a recovery inventory`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("installation"))
        assertEquals(Refinement.Refined(ManagedRecoveryRemovalProof.Absent), admitRecoveryRemoval(root))
        Files.delete(root)
        assertEquals(RecoveryRemovalOutcome.Removed, removeRetiredRecovery(root, ManagedRecoveryRemovalProof.Absent))
    }

    @Test
    fun `a private recovery bundle is foreign content and remains untouched`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("installation"))
        val bundle = Files.createDirectories(root.parent.resolve("recovery/installation"), privateDirectory)
        Files.setPosixFilePermissions(bundle.parent, PosixFilePermissions.fromString("rwx------"))
        val receipt = Files.writeString(bundle.resolve("receipt.json"), "retained bundle")
        assertEquals(Refinement.Rejected(RecoveryRemovalFailure.FOREIGN_CONTENT), admitRecoveryRemoval(root))
        assertEquals("retained bundle", Files.readString(receipt))
        assertTrue(Files.exists(root))
    }

    @Test
    fun `captured private empty recovery parent removes only after retirement`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("installation"))
        val directory = Files.createDirectory(root.parent.resolve("recovery"), privateDirectory)
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
        val directory = Files.createDirectory(root.parent.resolve("recovery"), privateDirectory)
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
        Files.createDirectory(directory, privateDirectory)
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN),
            removeRetiredRecovery(root, proof),
        )
        assertTrue(Files.exists(directory))
    }

    @Test
    fun `late recovery directory cannot be removed under an absent proof`(@TempDir temporary: Path) {
        val root = Files.createDirectory(temporary.toRealPath().resolve("installation"))
        val proof = refined(admitRecoveryRemoval(root))
        val directory = Files.createDirectory(root.parent.resolve("recovery"), privateDirectory)
        Files.delete(root)
        assertEquals(
            RecoveryRemovalOutcome.Rejected(RecoveryRemovalFailure.OWNERSHIP_UNPROVEN),
            removeRetiredRecovery(root, proof),
        )
        assertTrue(Files.exists(directory))
    }

    private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

    private fun <T, F> refined(value: Refinement<T, F>): T = assertInstanceOf<Refinement.Refined<T>>(value).value
}
