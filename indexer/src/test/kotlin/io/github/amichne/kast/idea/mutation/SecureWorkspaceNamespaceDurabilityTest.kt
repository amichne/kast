package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.contract.query.MutationScratchDirection
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryQuery
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SecureWorkspaceNamespaceDurabilityTest {
    @Test
    fun `create persists its final rename before reporting committed`() {
        val root = Files.createTempDirectory("kast-create-directory-durability").toRealPath()
        val target = root.resolve("Created.kt")
        val events = mutableListOf<String>()
        val transitions = mutableListOf<NamespaceTransition>()
        val barrier = ParentDirectoryDurabilityBarrier { _, transition ->
            assertTrue(Files.exists(target), "The native rename must happen before the parent sync")
            transitions += transition
            events += "parent-sync"
            ParentDirectoryDurabilityResult.Durable
        }

        val result = SecureWorkspaceMutation(
            workspaceRoot = root,
            beforeNoReplaceRename = { _, _ -> events += "before-rename" },
            parentDirectoryDurabilityBarrier = barrier,
        ).createFileRequiringExistingParents(target, "class Created\n")

        assertEquals(SecureWorkspaceMutationResult.Committed, result)
        assertEquals(listOf("before-rename", "parent-sync"), events)
        val transition = transitions.single() as NamespaceTransition.Rename
        assertEquals(SecureWorkspaceRenamePhase.FINAL_COMMIT, transition.phase)
        assertEquals(target.fileName.toString(), transition.destinationName)
    }

    @Test
    fun `rename durability failure is typed and cannot report create success`() {
        val root = Files.createTempDirectory("kast-create-directory-sync-failure").toRealPath()
        val target = root.resolve("Created.kt")
        val barrier = ParentDirectoryDurabilityBarrier { _, _ ->
            ParentDirectoryDurabilityResult.Failed(errno = 5)
        }

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = root,
                parentDirectoryDurabilityBarrier = barrier,
            ).createFileRequiringExistingParents(target, "class Created\n")
        }

        assertEquals("fsync-parent-directory", failure.details["nativeOperation"])
        assertEquals("rename", failure.details["namespaceTransition"])
        assertEquals("5", failure.details["errno"])
        assertEquals(target.toString(), failure.details["destinationPath"])
        assertTrue(Files.exists(target), "The applied but unproven rename must remain explicit")
    }

    @Test
    fun `replace and delete persist every successful rename and unlink`() {
        val replaceRoot = Files.createTempDirectory("kast-replace-directory-durability").toRealPath()
        val replaceTarget = replaceRoot.resolve("Replace.kt")
        val original = "class Original\n"
        Files.writeString(replaceTarget, original)
        val replaceTransitions = mutableListOf<NamespaceTransition>()

        SecureWorkspaceMutation(
            workspaceRoot = replaceRoot,
            parentDirectoryDurabilityBarrier = recordingBarrier(replaceTransitions),
        ).replaceFile(replaceTarget, FileHashing.sha256(original), "class Replacement\n")

        assertEquals(
            listOf(
                SecureWorkspaceRenamePhase.DETACH_TARGET,
                SecureWorkspaceRenamePhase.FINAL_COMMIT,
                SecureWorkspaceRenamePhase.MOVE_CLEANUP,
            ),
            replaceTransitions.filterIsInstance<NamespaceTransition.Rename>().map { it.phase },
        )
        assertEquals(1, replaceTransitions.filterIsInstance<NamespaceTransition.Unlink>().size)

        val deleteRoot = Files.createTempDirectory("kast-delete-directory-durability").toRealPath()
        val deleteTarget = deleteRoot.resolve("Delete.kt")
        Files.writeString(deleteTarget, original)
        val deleteTransitions = mutableListOf<NamespaceTransition>()

        SecureWorkspaceMutation(
            workspaceRoot = deleteRoot,
            parentDirectoryDurabilityBarrier = recordingBarrier(deleteTransitions),
        ).deleteFile(deleteTarget, FileHashing.sha256(original))

        assertEquals(
            listOf(
                SecureWorkspaceRenamePhase.DETACH_TARGET,
                SecureWorkspaceRenamePhase.FINAL_COMMIT,
                SecureWorkspaceRenamePhase.MOVE_CLEANUP,
                SecureWorkspaceRenamePhase.MOVE_CLEANUP,
            ),
            deleteTransitions.filterIsInstance<NamespaceTransition.Rename>().map { it.phase },
        )
        assertEquals(2, deleteTransitions.filterIsInstance<NamespaceTransition.Unlink>().size)
    }

    @Test
    fun `unlink durability failure is typed and cannot report replace success`() {
        val root = Files.createTempDirectory("kast-unlink-directory-sync-failure").toRealPath()
        val target = root.resolve("Replace.kt")
        val original = "class Original\n"
        val replacement = "class Replacement\n"
        Files.writeString(target, original)
        val barrier = ParentDirectoryDurabilityBarrier { _, transition ->
            if (transition is NamespaceTransition.Unlink) {
                ParentDirectoryDurabilityResult.Failed(errno = 5)
            } else {
                ParentDirectoryDurabilityResult.Durable
            }
        }

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(
                workspaceRoot = root,
                parentDirectoryDurabilityBarrier = barrier,
            ).replaceFile(target, FileHashing.sha256(original), replacement)
        }

        assertEquals("fsync-parent-directory", failure.details["nativeOperation"])
        assertEquals("unlink", failure.details["namespaceTransition"])
        assertEquals(replacement, Files.readString(target))
    }

    @Test
    fun `scratch cleanup persists its namespace transitions before returning recovery evidence`() {
        val root = Files.createTempDirectory("kast-scratch-directory-durability").toRealPath()
        val target = root.resolve("App.kt")
        val quarantine = root.resolve(".kast-quarantine-$ATTEMPT_ID-7")
        val prepared = root.resolve(".kast-prepared-$ATTEMPT_ID-7.tmp")
        val preparedCleanup = root.resolve(".kast-cleanup-$ATTEMPT_ID-7-prepared")
        val quarantineCleanup = root.resolve(".kast-cleanup-$ATTEMPT_ID-7-quarantine")
        Files.write(target, AFTER)
        Files.write(quarantine, BEFORE)
        val transitions = mutableListOf<NamespaceTransition>()

        SecureWorkspaceMutation(
            workspaceRoot = root,
            parentDirectoryDurabilityBarrier = recordingBarrier(transitions),
        ).recoverMutationScratch(
            MutationScratchRecoveryQuery(
                mutationAttemptId = ATTEMPT_ID,
                action = MutationScratchRecoveryAction.FINALIZE_POSTIMAGE,
                scratchDirection = MutationScratchDirection.FORWARD,
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

        assertEquals(
            listOf(SecureWorkspaceRenamePhase.MOVE_CLEANUP),
            transitions.filterIsInstance<NamespaceTransition.Rename>().map { it.phase },
        )
        assertEquals(1, transitions.filterIsInstance<NamespaceTransition.Unlink>().size)
    }

    private fun recordingBarrier(
        transitions: MutableList<NamespaceTransition>,
    ): ParentDirectoryDurabilityBarrier = ParentDirectoryDurabilityBarrier { _, transition ->
        transitions += transition
        ParentDirectoryDurabilityResult.Durable
    }

    private companion object {
        const val ATTEMPT_ID = "123e4567-e89b-42d3-a456-426614174020"
        val BEFORE = "before".toByteArray()
        val AFTER = "after".toByteArray()
    }
}
