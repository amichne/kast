package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.contract.query.MutationScratchDirection
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryQuery
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.parsed
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutationScratchFailureTotalizationTest {
    @Test
    fun `post-move rollback retains an ordinary restoration failure`() {
        val fixture = fixture()
        Files.write(fixture.quarantine, BEFORE)
        Files.write(fixture.prepared, AFTER)
        val restorationFailure = IllegalStateException("force ordinary scratch restoration failure")
        var changedPrepared = false
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            beforeNoReplaceRename = { _, phase ->
                when (phase) {
                    SecureWorkspaceRenamePhase.FINAL_COMMIT -> if (!changedPrepared) {
                        Files.write(fixture.prepared, FOREIGN)
                        changedPrepared = true
                    }

                    SecureWorkspaceRenamePhase.RESTORE_TARGET -> throw restorationFailure
                    else -> Unit
                }
            },
        )

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            fixture.recover(
                action = MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                direction = MutationScratchDirection.FORWARD,
                mutation = mutation,
            )
        }

        assertEquals(
            restorationFailure.message,
            failure.details["restorationFailure"],
            failure.details.toString(),
        )
        assertEquals(listOf(restorationFailure), failure.suppressed.toList())
        assertArrayEquals(FOREIGN, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `scratch final commit durability failure retains typed transition evidence`() {
        val fixture = fixture()
        Files.write(fixture.quarantine, BEFORE)
        Files.write(fixture.prepared, AFTER)
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            parentDirectoryDurabilityBarrier = ParentDirectoryDurabilityBarrier { _, transition ->
                if (transition is NamespaceTransition.Rename &&
                    transition.phase == SecureWorkspaceRenamePhase.FINAL_COMMIT
                ) {
                    ParentDirectoryDurabilityResult.Failed(errno = 5)
                } else {
                    ParentDirectoryDurabilityResult.Durable
                }
            },
        )

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            fixture.recover(
                action = MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                direction = MutationScratchDirection.FORWARD,
                mutation = mutation,
            )
        }

        assertEquals("fsync-parent-directory", failure.details["nativeOperation"])
        assertEquals("rename", failure.details["namespaceTransition"])
        assertEquals(SecureWorkspaceRenamePhase.FINAL_COMMIT.name, failure.details["renamePhase"])
        assertEquals("5", failure.details["errno"])
        assertArrayEquals(AFTER, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `scratch recovery records detach and commit durability phases`() {
        val fixture = fixture()
        Files.write(fixture.target, AFTER)
        Files.write(fixture.prepared, BEFORE)
        val phases = mutableListOf<SecureWorkspaceRenamePhase>()
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            parentDirectoryDurabilityBarrier = ParentDirectoryDurabilityBarrier { _, transition ->
                if (transition is NamespaceTransition.Rename) phases += transition.phase
                ParentDirectoryDurabilityResult.Durable
            },
        )

        fixture.recover(
            action = MutationScratchRecoveryAction.RESTORE_PREIMAGE,
            direction = MutationScratchDirection.RESTORE_PREIMAGE,
            mutation = mutation,
        )

        assertEquals(
            listOf(
                SecureWorkspaceRenamePhase.DETACH_TARGET,
                SecureWorkspaceRenamePhase.FINAL_COMMIT,
            ),
            phases.take(2),
        )
        assertArrayEquals(BEFORE, Files.readAllBytes(fixture.target))
    }

    @Test
    fun `scratch cleanup retains ordinary cleanup restoration failure and recovery path`() {
        val fixture = fixture()
        Files.write(fixture.target, AFTER)
        Files.write(fixture.quarantine, BEFORE)
        val restorationFailure = IllegalArgumentException("force ordinary cleanup restoration failure")
        val mutation = SecureWorkspaceMutation(
            workspaceRoot = fixture.root,
            beforeCleanupUnlink = { error("force cleanup retention") },
            beforeNoReplaceRename = { _, phase ->
                if (phase == SecureWorkspaceRenamePhase.RESTORE_CLEANUP) throw restorationFailure
            },
        )

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            fixture.recover(
                action = MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                direction = MutationScratchDirection.FORWARD,
                mutation = mutation,
            )
        }

        assertEquals(restorationFailure.message, failure.details["restorationFailure"])
        assertEquals("1", failure.details["recoveryFilePathCount"])
        val retained = Path.of(failure.details.getValue("recoveryFilePath.0"))
        assertEquals(fixture.quarantineCleanup, retained)
        assertTrue(Files.exists(retained))
        assertSame(restorationFailure, failure.suppressed.single())
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("kast-scratch-failure-totalization").toRealPath()
        return Fixture(root, root.resolve("App.kt"))
    }

    private data class Fixture(
        val root: Path,
        val target: Path,
    ) {
        val quarantine: Path = root.resolve(".kast-quarantine-$ATTEMPT_ID-7")
        val prepared: Path = root.resolve(".kast-prepared-$ATTEMPT_ID-7.tmp")
        val preparedCleanup: Path = root.resolve(".kast-cleanup-$ATTEMPT_ID-7-prepared")
        val quarantineCleanup: Path = root.resolve(".kast-cleanup-$ATTEMPT_ID-7-quarantine")

        fun recover(
            action: MutationScratchRecoveryAction,
            direction: MutationScratchDirection,
            mutation: SecureWorkspaceMutation,
        ) = mutation.recoverMutationScratch(
            MutationScratchRecoveryQuery(
                mutationAttemptId = ATTEMPT_ID,
                action = action,
                scratchDirection = direction,
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
        const val ATTEMPT_ID = "123e4567-e89b-42d3-a456-426614174023"
        val BEFORE = "before".toByteArray()
        val AFTER = "after".toByteArray()
        val FOREIGN = "foreign".toByteArray()
    }
}
