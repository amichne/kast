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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SecureWorkspaceHardExclusionTest {
    @Test
    fun `raw exact mutation rejects every hard-excluded workspace component`() {
        HARD_EXCLUDED_NAMES.forEach { excludedName ->
            val root = Files.createTempDirectory("kast-hard-excluded-cas").toRealPath()
            val target = Files.createDirectories(root.resolve(excludedName).resolve("generated"))
                .resolve("Target.kt")
            val before = "class Before\n".toByteArray()
            Files.write(target, before)

            val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
                SecureWorkspaceMutation(root).replaceFile(
                    target,
                    FileHashing.sha256(before),
                    "class After\n".toByteArray(),
                )
            }

            assertEquals("hard-excluded-target", failure.details["nativeOperation"])
            assertEquals(excludedName, failure.details["hardExcludedComponent"])
            assertArrayEquals(before, Files.readAllBytes(target))
        }
    }

    @Test
    fun `scratch recovery cannot bypass the central hard-exclusion boundary`() {
        val root = Files.createTempDirectory("kast-hard-excluded-recovery").toRealPath()
        val excludedRoot = Files.createDirectories(root.resolve("build").resolve("generated"))
        val target = excludedRoot.resolve("Target.kt")
        val quarantine = excludedRoot.resolve(".kast-quarantine-$ATTEMPT_ID-7")
        val prepared = excludedRoot.resolve(".kast-prepared-$ATTEMPT_ID-7.tmp")
        val preparedCleanup = excludedRoot.resolve(".kast-cleanup-$ATTEMPT_ID-7-prepared")
        val quarantineCleanup = excludedRoot.resolve(".kast-cleanup-$ATTEMPT_ID-7-quarantine")
        Files.write(target, AFTER)
        Files.write(quarantine, BEFORE)

        val failure = assertThrows(UnsafeWorkspaceMutationException::class.java) {
            SecureWorkspaceMutation(root).recoverMutationScratch(
                MutationScratchRecoveryQuery(
                    mutationAttemptId = ATTEMPT_ID,
                    action = MutationScratchRecoveryAction.RESTORE_PREIMAGE,
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
        }

        assertEquals("hard-excluded-target", failure.details["nativeOperation"])
        assertArrayEquals(AFTER, Files.readAllBytes(target))
        assertArrayEquals(BEFORE, Files.readAllBytes(quarantine))
    }

    @Test
    fun `central mutation permits generated roots outside the hard-excluded set`() {
        val root = Files.createTempDirectory("kast-permitted-generated-cas").toRealPath()
        val target = Files.createDirectories(root.resolve("generated").resolve("kotlin"))
            .resolve("Target.kt")
        val before = "class Before\n"
        val after = "class After\n"
        Files.writeString(target, before)

        val result = SecureWorkspaceMutation(root).replaceFile(target, FileHashing.sha256(before), after)

        assertEquals(SecureWorkspaceMutationResult.Committed, result)
        assertEquals(after, Files.readString(target))
    }

    private companion object {
        val HARD_EXCLUDED_NAMES = listOf("build", ".gradle", ".idea", ".kotlin", "out")
        const val ATTEMPT_ID = "123e4567-e89b-42d3-a456-426614174022"
        val BEFORE = "before".toByteArray()
        val AFTER = "after".toByteArray()
    }
}
