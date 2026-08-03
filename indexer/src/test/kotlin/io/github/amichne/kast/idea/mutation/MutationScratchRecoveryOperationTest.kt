package io.github.amichne.kast.idea.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.contract.query.MutationScratchDirection
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryQuery
import io.github.amichne.kast.api.contract.result.MutationScratchTargetState
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class MutationScratchRecoveryOperationTest {
    @Test
    fun `forward restore rejects zero preimage sources before deleting a postimage target`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.target, AFTER)

        assertThrows(UnsafeWorkspaceMutationException::class.java) {
            fixture.recover(MutationScratchRecoveryAction.RESTORE_PREIMAGE, MutationScratchDirection.FORWARD)
        }

        assertArrayEquals(AFTER, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `forward restore rejects two preimage sources before deleting a postimage target`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.target, AFTER)
        Files.write(fixture.quarantine, BEFORE)
        Files.write(fixture.quarantineCleanup, BEFORE)

        assertThrows(UnsafeWorkspaceMutationException::class.java) {
            fixture.recover(MutationScratchRecoveryAction.RESTORE_PREIMAGE, MutationScratchDirection.FORWARD)
        }

        assertArrayEquals(AFTER, Files.readAllBytes(fixture.target))
        assertArrayEquals(BEFORE, Files.readAllBytes(fixture.quarantine))
        assertArrayEquals(BEFORE, Files.readAllBytes(fixture.quarantineCleanup))
    }

    @Test
    fun `restore direction resumes a prepared original preimage and removes reverse input scratch`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.quarantine, AFTER)
        Files.write(fixture.prepared, BEFORE)

        val result = fixture.recover(
            MutationScratchRecoveryAction.RESTORE_PREIMAGE,
            MutationScratchDirection.RESTORE_PREIMAGE,
        )

        assertEquals(MutationScratchTargetState.PRESENT, result.targetState)
        assertEquals(FileHashing.sha256(BEFORE), result.targetSha256?.value)
        assertArrayEquals(BEFORE, Files.readAllBytes(fixture.target))
        fixture.assertScratchAbsent()
    }

    @Test
    fun `restore direction materializes an exact nonempty or empty present preimage after q-only crash`() {
        listOf(BEFORE, byteArrayOf()).forEach { exactPreimage ->
            val fixture = fixture(preimage = exactPreimage, postimage = AFTER)
            Files.write(fixture.quarantine, AFTER)

            val result = fixture.recover(
                MutationScratchRecoveryAction.RESTORE_PREIMAGE,
                MutationScratchDirection.RESTORE_PREIMAGE,
            )

            assertEquals(MutationScratchTargetState.PRESENT, result.targetState)
            assertEquals(FileHashing.sha256(exactPreimage), result.targetSha256?.value)
            assertArrayEquals(exactPreimage, Files.readAllBytes(fixture.target))
            fixture.assertScratchAbsent()
        }
    }

    @Test
    fun `restore direction removes an exact empty delete reservation for an absent original`() {
        val fixture = fixture(preimage = null, postimage = AFTER)
        Files.write(fixture.target, byteArrayOf())
        Files.write(fixture.quarantine, AFTER)

        val result = fixture.recover(
            MutationScratchRecoveryAction.RESTORE_PREIMAGE,
            MutationScratchDirection.RESTORE_PREIMAGE,
        )

        assertEquals(MutationScratchTargetState.ABSENT, result.targetState)
        assertFalse(Files.exists(fixture.target))
        fixture.assertScratchAbsent()
    }

    @Test
    fun `forward finalize moves exactly one prepared postimage into an absent target`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.quarantine, BEFORE)
        Files.write(fixture.preparedCleanup, AFTER)

        val result = fixture.recover(
            MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
            MutationScratchDirection.FORWARD,
        )

        assertEquals(MutationScratchTargetState.PRESENT, result.targetState)
        assertEquals(FileHashing.sha256(AFTER), result.targetSha256?.value)
        assertArrayEquals(AFTER, Files.readAllBytes(fixture.target))
        fixture.assertScratchAbsent()
    }

    @Test
    fun `forward finalize rejects zero and two prepared sources without writing`() {
        listOf(0, 2).forEach { sourceCount ->
            val fixture = fixture(preimage = BEFORE, postimage = AFTER)
            Files.write(fixture.quarantine, BEFORE)
            if (sourceCount > 0) Files.write(fixture.prepared, AFTER)
            if (sourceCount > 1) Files.write(fixture.preparedCleanup, AFTER)

            assertThrows(UnsafeWorkspaceMutationException::class.java) {
                fixture.recover(
                    MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                    MutationScratchDirection.FORWARD,
                )
            }

            assertFalse(Files.exists(fixture.target))
            assertArrayEquals(BEFORE, Files.readAllBytes(fixture.quarantine))
            assertEquals(sourceCount > 0, Files.exists(fixture.prepared))
            assertEquals(sourceCount > 1, Files.exists(fixture.preparedCleanup))
        }
    }

    @Test
    fun `foreign internal scratch blocks recovery before any target or owned scratch write`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        val foreign = fixture.root.resolve(".kast-prepared-123e4567-e89b-42d3-a456-426614174099-9.tmp")
        Files.write(fixture.target, AFTER)
        Files.write(fixture.quarantine, BEFORE)
        Files.write(foreign, FOREIGN)

        assertThrows(UnsafeWorkspaceMutationException::class.java) {
            fixture.recover(MutationScratchRecoveryAction.RESTORE_PREIMAGE, MutationScratchDirection.FORWARD)
        }

        assertArrayEquals(AFTER, Files.readAllBytes(fixture.target))
        assertArrayEquals(BEFORE, Files.readAllBytes(fixture.quarantine))
        assertArrayEquals(FOREIGN, Files.readAllBytes(foreign))
    }

    @Test
    fun `in-place cleanup overwrite is retained and never unlinked`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.target, AFTER)
        Files.write(fixture.quarantine, BEFORE)
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            beforeCleanupUnlink = { cleanupPath ->
                if (cleanupPath == fixture.quarantineCleanup) Files.write(cleanupPath, FOREIGN)
            },
        )

        assertThrows(UnsafeWorkspaceMutationException::class.java) {
            fixture.recover(
                action = MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                direction = MutationScratchDirection.FORWARD,
                mutation = mutation,
            )
        }

        assertArrayEquals(AFTER, Files.readAllBytes(fixture.target))
        assertTrue(Files.exists(fixture.quarantine), "Changed cleanup entry must be restored, not unlinked")
        assertArrayEquals(FOREIGN, Files.readAllBytes(fixture.quarantine))
    }

    @Test
    fun `scratch cleanup cancellation preserves identity with retained path evidence`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.target, AFTER)
        Files.write(fixture.quarantine, BEFORE)
        val cancellation = CancellationException("cancel scratch cleanup")
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            beforeCleanupUnlink = { throw cancellation },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            fixture.recover(
                action = MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                direction = MutationScratchDirection.FORWARD,
                mutation = mutation,
            )
        }

        assertSame(cancellation, thrown)
        val evidence = thrown.suppressed.filterIsInstance<UnsafeWorkspaceMutationException>().single()
        assertEquals("1", evidence.details["recoveryFilePathCount"])
        val retained = Path.of(evidence.details.getValue("recoveryFilePath.0"))
        assertTrue(Files.exists(retained))
        assertArrayEquals(BEFORE, Files.readAllBytes(retained))
        assertArrayEquals(AFTER, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `scratch target rollback preserves IDEA cancellation over the triggering failure`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.target, AFTER)
        Files.write(fixture.prepared, BEFORE)
        val cancellation = ProcessCanceledException()
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            beforeNoReplaceRename = { _, phase ->
                when (phase) {
                    SecureWorkspaceRenamePhase.FINAL_COMMIT -> error("force desired-image move failure")
                    SecureWorkspaceRenamePhase.RESTORE_TARGET -> throw cancellation
                    else -> Unit
                }
            },
        )

        val thrown = assertThrows(ProcessCanceledException::class.java) {
            fixture.recover(
                action = MutationScratchRecoveryAction.RESTORE_PREIMAGE,
                direction = MutationScratchDirection.RESTORE_PREIMAGE,
                mutation = mutation,
            )
        }

        assertSame(cancellation, thrown)
        assertTrue(thrown.suppressed.single() is UnsafeWorkspaceMutationException)
        assertFalse(Files.exists(fixture.target))
        assertArrayEquals(AFTER, Files.readAllBytes(fixture.quarantine))
    }

    @Test
    fun `scratch post-move rollback preserves task cancellation with typed evidence`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.quarantine, BEFORE)
        Files.write(fixture.prepared, AFTER)
        val cancellation = CancellationException("cancel scratch post-move rollback")
        var changedPrepared = false
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            beforeNoReplaceRename = { _, phase ->
                when (phase) {
                    SecureWorkspaceRenamePhase.FINAL_COMMIT -> if (!changedPrepared) {
                        Files.write(fixture.prepared, FOREIGN)
                        changedPrepared = true
                    }

                    SecureWorkspaceRenamePhase.RESTORE_TARGET -> throw cancellation
                    else -> Unit
                }
            },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            fixture.recover(
                action = MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                direction = MutationScratchDirection.FORWARD,
                mutation = mutation,
            )
        }

        assertSame(cancellation, thrown)
        assertTrue(thrown.suppressed.isNotEmpty())
        assertTrue(thrown.suppressed.all { evidence -> evidence is UnsafeWorkspaceMutationException })
        assertArrayEquals(FOREIGN, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `scratch cleanup restoration preserves cancellation over an earlier cleanup failure`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.target, AFTER)
        Files.write(fixture.quarantine, BEFORE)
        val cancellation = CancellationException("cancel scratch cleanup restoration")
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            beforeCleanupUnlink = { error("force cleanup restoration") },
            beforeNoReplaceRename = { _, phase ->
                if (phase == SecureWorkspaceRenamePhase.RESTORE_CLEANUP) throw cancellation
            },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            fixture.recover(
                action = MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                direction = MutationScratchDirection.FORWARD,
                mutation = mutation,
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(2, thrown.suppressed.size)
        assertTrue(thrown.suppressed.all { evidence -> evidence is UnsafeWorkspaceMutationException })
        assertArrayEquals(AFTER, Files.readAllBytes(fixture.target))
        assertArrayEquals(BEFORE, Files.readAllBytes(fixture.quarantineCleanup))
    }

    @Test
    fun `scratch cleanup keeps the first cancellation primary when restoration also cancels`() {
        val fixture = fixture(preimage = BEFORE, postimage = AFTER)
        Files.write(fixture.target, AFTER)
        Files.write(fixture.quarantine, BEFORE)
        val primaryCancellation = CancellationException("cancel scratch cleanup")
        val restorationCancellation = ProcessCanceledException()
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            beforeCleanupUnlink = { throw primaryCancellation },
            beforeNoReplaceRename = { _, phase ->
                if (phase == SecureWorkspaceRenamePhase.RESTORE_CLEANUP) throw restorationCancellation
            },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            fixture.recover(
                action = MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                direction = MutationScratchDirection.FORWARD,
                mutation = mutation,
            )
        }

        assertSame(primaryCancellation, thrown)
        assertTrue(thrown.suppressed.isNotEmpty())
        assertTrue(thrown.suppressed.all { evidence -> evidence is UnsafeWorkspaceMutationException })
        assertArrayEquals(AFTER, Files.readAllBytes(fixture.target))
        assertArrayEquals(BEFORE, Files.readAllBytes(fixture.quarantineCleanup))
    }

    private fun fixture(preimage: ByteArray?, postimage: ByteArray): Fixture {
        val root = Files.createTempDirectory("kast-scratch-recovery").toRealPath()
        val target = root.resolve("App.kt")
        return Fixture(root, target, preimage?.copyOf(), postimage.copyOf())
    }

    private data class Fixture(
        val root: Path,
        val target: Path,
        val preimage: ByteArray?,
        val postimage: ByteArray,
    ) {
        val quarantine: Path = root.resolve(".kast-quarantine-$ATTEMPT_ID-7")
        val prepared: Path = root.resolve(".kast-prepared-$ATTEMPT_ID-7.tmp")
        val preparedCleanup: Path = root.resolve(".kast-cleanup-$ATTEMPT_ID-7-prepared")
        val quarantineCleanup: Path = root.resolve(".kast-cleanup-$ATTEMPT_ID-7-quarantine")

        fun recover(
            action: MutationScratchRecoveryAction,
            direction: MutationScratchDirection,
            mutation: SecureWorkspaceMutation = SecureWorkspaceMutation(root),
        ) = mutation.recoverMutationScratch(
            MutationScratchRecoveryQuery(
                mutationAttemptId = ATTEMPT_ID,
                action = action,
                scratchDirection = direction,
                targetFilePath = target.toString(),
                preimage = preimage?.let { bytes ->
                    MutationScratchRecoveryPreimage.Present(ExactByteImage.of(bytes))
                } ?: MutationScratchRecoveryPreimage.Absent,
                postimage = ExactByteImage.of(postimage),
                scratch = MutationScratchSet(
                    targetFilePath = target.toString(),
                    quarantinePath = quarantine.toString(),
                    preparedPath = prepared.toString(),
                    preparedCleanupPath = preparedCleanup.toString(),
                    quarantineCleanupPath = quarantineCleanup.toString(),
                ),
            ).parsed(),
        )

        fun assertScratchAbsent() {
            listOf(quarantine, prepared, preparedCleanup, quarantineCleanup).forEach { path ->
                assertFalse(Files.exists(path), "Expected scratch role to be absent: $path")
            }
        }
    }

    private companion object {
        const val ATTEMPT_ID = "123e4567-e89b-42d3-a456-426614174000"
        val BEFORE = "before".toByteArray()
        val AFTER = "after".toByteArray()
        val FOREIGN = "foreign".toByteArray()
    }
}
