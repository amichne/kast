package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.ExactFileImageBase64
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.FileOperation
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.ExactFileImageQuery
import io.github.amichne.kast.api.contract.query.MutationScratchInspectQuery
import io.github.amichne.kast.api.contract.query.MutationScratchDirection
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryQuery
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.contract.result.MutationScratchInspectResult
import io.github.amichne.kast.api.contract.result.MutationScratchObservation
import io.github.amichne.kast.api.contract.result.MutationScratchOwnership
import io.github.amichne.kast.api.contract.result.MutationScratchRole
import io.github.amichne.kast.api.contract.result.MutationScratchState
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MutationScratchContractTest {
    private val attemptId = "123e4567-e89b-42d3-a456-426614174000"
    private val target = "/workspace/src/App.kt"

    @Test
    fun `verified exact CAS and apply parse one canonical v4 attempt with exact scratch roles`() {
        val scratch = scratchSet(target, transitionIndex = 7)
        val postimage = "after".toByteArray()
        val parsedCas = ExactFileImageQuery(
            filePath = ExactFileImagePath(target),
            expectedCurrentSha256 = ExactFileImageSha256(FileHashing.sha256("before")),
            contentBase64 = ExactFileImageBase64(Base64.getEncoder().encodeToString(postimage)),
            expectedResultSha256 = ExactFileImageSha256(FileHashing.sha256(postimage)),
            mutationAttemptId = attemptId,
            mutationScratch = scratch,
        ).parsed()
        val parsedApply = ApplyEditsQuery(
            edits = emptyList(),
            fileHashes = emptyList(),
            fileOperations = listOf(
                FileOperation.CreateFile(
                    target,
                    "after",
                    io.github.amichne.kast.api.contract.CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS,
                ),
            ),
            mutationAttemptId = attemptId,
            mutationScratchSets = listOf(scratch),
        ).parsed()

        assertEquals(attemptId, parsedCas.mutationAttemptId?.value)
        assertEquals(scratch.preparedPath, parsedCas.mutationScratch?.preparedPath?.value)
        assertEquals(7, parsedCas.mutationScratch?.transitionIndex)
        assertEquals(attemptId, parsedApply.mutationAttemptId?.value)
        assertEquals(target, parsedApply.mutationScratchSets.single().targetFilePath.value)
    }

    @Test
    fun `verified mutation queries reject non-v4 IDs missing scratch and role mismatch`() {
        val postimage = "after".toByteArray()
        val base = ExactFileImageQuery(
            filePath = ExactFileImagePath(target),
            expectedCurrentSha256 = ExactFileImageSha256(FileHashing.sha256("before")),
            contentBase64 = ExactFileImageBase64(Base64.getEncoder().encodeToString(postimage)),
            expectedResultSha256 = ExactFileImageSha256(FileHashing.sha256(postimage)),
        )

        assertThrows(ValidationException::class.java) {
            base.copy(mutationAttemptId = "123e4567-e89b-12d3-a456-426614174000").parsed()
        }
        assertThrows(ValidationException::class.java) {
            base.copy(mutationAttemptId = attemptId).parsed()
        }
        assertThrows(ValidationException::class.java) {
            base.copy(mutationScratch = scratchSet(target, 0)).parsed()
        }
        assertThrows(ValidationException::class.java) {
            base.copy(
                mutationAttemptId = attemptId,
                mutationScratch = scratchSet("/workspace/src/Other.kt", 0),
            ).parsed()
        }

        val firstTarget = "/workspace/first/One.kt"
        val secondTarget = "/workspace/second/Two.kt"
        assertThrows(ValidationException::class.java) {
            ApplyEditsQuery(
                edits = emptyList(),
                fileHashes = emptyList(),
                fileOperations = listOf(
                    FileOperation.CreateFile(
                        firstTarget,
                        "one",
                        io.github.amichne.kast.api.contract.CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS,
                    ),
                    FileOperation.CreateFile(
                        secondTarget,
                        "two",
                        io.github.amichne.kast.api.contract.CreateFileParentPolicy.REQUIRE_EXISTING_PARENTS,
                    ),
                ),
                mutationAttemptId = attemptId,
                mutationScratchSets = listOf(
                    scratchSet(firstTarget, transitionIndex = 9),
                    scratchSet(secondTarget, transitionIndex = 9),
                ),
            ).parsed()
        }
    }

    @Test
    fun `scratch inspect and recovery retain the closed journal authority`() {
        val olderOwner = "123e4567-e89b-42d3-a456-426614174099"
        val scratch = scratchSet(target, transitionIndex = 0, ownerAttemptId = olderOwner)
        val inspect = MutationScratchInspectQuery(
            mutationAttemptId = attemptId,
            workspaceRelativeParentPaths = listOf(".", "src"),
            ownedScratchSets = listOf(scratch),
        ).parsed()
        val recovery = MutationScratchRecoveryQuery(
            mutationAttemptId = attemptId,
            action = MutationScratchRecoveryAction.RESTORE_PREIMAGE,
            scratchDirection = MutationScratchDirection.FORWARD,
            targetFilePath = target,
            preimage = MutationScratchRecoveryPreimage.Present(ExactByteImage.of("before".toByteArray())),
            postimage = ExactByteImage.of("after".toByteArray()),
            scratch = scratch,
        ).parsed()

        assertEquals(listOf(".", "src"), inspect.workspaceRelativeParentPaths.map { it.value })
        assertEquals(attemptId, recovery.mutationAttemptId.value)
        assertEquals(olderOwner, recovery.scratch.ownerAttemptId.value)
        assertEquals(target, recovery.scratch.targetFilePath.value)
        assertEquals(MutationScratchDirection.FORWARD, recovery.scratchDirection)
    }

    @Test
    fun `inspect result rejects duplicate paths and inspect query rejects overlapping role paths`() {
        val observation = MutationScratchObservation(
            filePath = "/workspace/src/.kast-prepared-$attemptId-0.tmp",
            ownership = MutationScratchOwnership.OWNED,
            role = MutationScratchRole.PREPARED,
            state = MutationScratchState.ABSENT,
        )
        assertThrows(IllegalArgumentException::class.java) {
            MutationScratchInspectResult(
                mutationAttemptId = io.github.amichne.kast.api.contract.MutationAttemptId.parse(attemptId),
                observations = listOf(observation, observation),
            )
        }

        val first = scratchSet(target, transitionIndex = 0)
        val second = scratchSet("/workspace/src/Other.kt", transitionIndex = 0)
        assertThrows(ValidationException::class.java) {
            MutationScratchInspectQuery(
                mutationAttemptId = attemptId,
                workspaceRelativeParentPaths = listOf("src"),
                ownedScratchSets = listOf(first, second).sortedBy { scratch ->
                    listOf(
                        scratch.targetFilePath,
                        scratch.quarantinePath,
                        scratch.preparedPath,
                        scratch.preparedCleanupPath,
                        scratch.quarantineCleanupPath,
                    ).joinToString("\u0000")
                },
            ).parsed()
        }
    }

    private fun scratchSet(
        targetFilePath: String,
        transitionIndex: Int,
        ownerAttemptId: String = attemptId,
    ): MutationScratchSet {
        val parent = targetFilePath.substringBeforeLast('/')
        return MutationScratchSet(
            targetFilePath = targetFilePath,
            quarantinePath = "$parent/.kast-quarantine-$ownerAttemptId-$transitionIndex",
            preparedPath = "$parent/.kast-prepared-$ownerAttemptId-$transitionIndex.tmp",
            preparedCleanupPath = "$parent/.kast-cleanup-$ownerAttemptId-$transitionIndex-prepared",
            quarantineCleanupPath = "$parent/.kast-cleanup-$ownerAttemptId-$transitionIndex-quarantine",
        )
    }
}
