package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.contract.query.MutationScratchDirection
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryQuery
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureMutationRecoveryTotalizationTest {
    @Test
    fun `scratch desired move and detached restoration failures retain combined typed evidence`() {
        val fixture = scratchFixture()
        Files.write(fixture.target, AFTER)
        Files.write(fixture.prepared, BEFORE)
        val desiredMoveFailure = IllegalStateException("force desired-image move failure")
        val restorationFailure = IllegalArgumentException("force detached-target restoration failure")
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            beforeNoReplaceRename = { _, phase ->
                when (phase) {
                    SecureWorkspaceRenamePhase.FINAL_COMMIT -> throw desiredMoveFailure
                    SecureWorkspaceRenamePhase.RESTORE_TARGET -> throw restorationFailure
                    else -> Unit
                }
            },
        )

        val thrown = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            fixture.recover(mutation)
        }

        assertSame(desiredMoveFailure, thrown.cause)
        assertEquals(listOf(restorationFailure), thrown.suppressed.toList())
        assertEquals(desiredMoveFailure.message, thrown.details["cause"])
        assertEquals(restorationFailure.message, thrown.details["restorationFailure"])
        val retained = recoveryPaths(thrown)
        assertTrue(retained.contains(fixture.target))
        assertTrue(retained.contains(fixture.prepared))
        assertTrue(retained.contains(fixture.quarantine))
        assertFalse(Files.exists(fixture.target))
        assertArrayEquals(AFTER, Files.readAllBytes(fixture.quarantine))
    }

    @Test
    fun `hash conflict restoration failure retains the conflict and quarantine path`() {
        val root = Files.createTempDirectory("kast-hash-restoration-totalization").toRealPath()
        val target = root.resolve("HashConflict.kt")
        val original = "class Original\n"
        val restorationFailure = IllegalStateException("force hash-conflict restoration failure")
        Files.writeString(target, original)

        val thrown = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = root,
                beforeNoReplaceRename = { _, phase ->
                    if (phase == SecureWorkspaceRenamePhase.RESTORE_TARGET) throw restorationFailure
                },
            ).replaceFile(target, FileHashing.sha256("stale"), "class Replacement\n")
        }

        assertTrue(thrown.cause is ConflictException)
        assertEquals(listOf(restorationFailure), thrown.suppressed.toList())
        assertEquals(restorationFailure.message, thrown.details["restorationFailure"])
        val recoveryPath = Path.of(thrown.details.getValue("recoveryFilePath"))
        assertFalse(Files.exists(target))
        assertTrue(Files.exists(recoveryPath))
        assertEquals(original, Files.readString(recoveryPath))
    }

    @Test
    fun `unopened quarantine double failure retains primary restoration and recovery path`() {
        val root = Files.createTempDirectory("kast-unopened-restoration-totalization").toRealPath()
        val target = root.resolve("Unreadable.kt")
        val original = "class Unreadable\n"
        val restorationFailure = IllegalStateException("force unopened-quarantine restoration failure")
        Files.writeString(target, original)
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("---------"))

        val thrown = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = root,
                beforeNoReplaceRename = { _, phase ->
                    if (phase == SecureWorkspaceRenamePhase.RESTORE_TARGET) throw restorationFailure
                },
            ).replaceFile(target, FileHashing.sha256(original), "class Replacement\n")
        }

        assertTrue(thrown.cause is UnsafeWorkspaceMutationException)
        assertEquals("openat-quarantine", (thrown.cause as UnsafeWorkspaceMutationException).details["nativeOperation"])
        assertEquals(listOf(restorationFailure), thrown.suppressed.toList())
        assertEquals(restorationFailure.message, thrown.details["restorationFailure"])
        val recoveryPath = Path.of(thrown.details.getValue("recoveryFilePath"))
        assertFalse(Files.exists(target))
        assertTrue(Files.exists(recoveryPath))
    }

    private fun scratchFixture(): ScratchFixture {
        val root = Files.createTempDirectory("kast-scratch-totalization").toRealPath()
        return ScratchFixture(root, root.resolve("App.kt"))
    }

    private fun recoveryPaths(failure: UnsafeWorkspaceMutationException): List<Path> =
        (0 until failure.details.getValue("recoveryFilePathCount").toInt()).map { index ->
            Path.of(failure.details.getValue("recoveryFilePath.$index"))
        }

    private data class ScratchFixture(
        val root: Path,
        val target: Path,
    ) {
        val quarantine: Path = root.resolve(".kast-quarantine-$ATTEMPT_ID-7")
        val prepared: Path = root.resolve(".kast-prepared-$ATTEMPT_ID-7.tmp")
        private val preparedCleanup: Path = root.resolve(".kast-cleanup-$ATTEMPT_ID-7-prepared")
        private val quarantineCleanup: Path = root.resolve(".kast-cleanup-$ATTEMPT_ID-7-quarantine")

        fun recover(mutation: SecureWorkspaceMutation) = mutation.recoverMutationScratch(
            MutationScratchRecoveryQuery(
                mutationAttemptId = ATTEMPT_ID,
                action = MutationScratchRecoveryAction.RESTORE_PREIMAGE,
                scratchDirection = MutationScratchDirection.RESTORE_PREIMAGE,
                targetFilePath = target.toString(),
                preimage = MutationScratchRecoveryPreimage.Present(ExactByteImage.of(BEFORE)),
                postimage = ExactByteImage.of(AFTER),
                scratch = MutationScratchSet(
                    targetFilePath = target.toString(),
                    quarantinePath = quarantine.toString(),
                    preparedPath = prepared.toString(),
                    preparedCleanupPath = preparedCleanup.toString(),
                    quarantineCleanupPath = quarantineCleanup.toString(),
                ),
            ).parsed(),
        )
    }

    private companion object {
        const val ATTEMPT_ID = "123e4567-e89b-42d3-a456-426614174021"
        val BEFORE = "before".toByteArray()
        val AFTER = "after".toByteArray()
    }
}
