package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationScratchSet
import java.nio.file.Path
import io.github.amichne.kast.idea.*

/**
 * Performs workspace mutations relative to held POSIX directory descriptors.
 *
 * The walk starts at the filesystem root and refuses symlinks for every
 * component. Once a directory is open, later symlink replacement cannot
 * redirect resolution away from that held directory identity. Existing
 * targets are detached before descriptor validation; final-name commits and
 * restoration use no-replace namespace operations. Best-effort cleanup moves
 * entries behind randomized internal names and verifies their device/inode
 * identity immediately before unlinking. Deliberate races against those
 * internal names are outside this boundary; a cleanup failure retains and
 * reports a recovery path instead of hiding a committed mutation.
 */
internal class SecureWorkspaceMutation(
    workspaceRoot: Path,
    internal val afterTargetDetached: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    internal val beforePreparedFileCreation: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    internal val beforeFinalCommit: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    internal val beforeNoReplaceRename: (Path, SecureWorkspaceRenamePhase) -> Unit = { _, _ -> },
    internal val afterDeleteReservationCommitted: (Path) -> Unit = {},
    internal val beforeCleanupUnlink: (Path) -> Unit = {},
    internal val parentDirectoryDurabilityBarrier: ParentDirectoryDurabilityBarrier =
        NativeParentDirectoryDurabilityBarrier,
) {
    internal val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()

    fun createFile(target: Path, content: String): SecureWorkspaceMutationResult =
        withParentDirectoryDurabilityEvidence {
            createFileExact(target, strictUtf8Bytes(content), createParents = true)
        }

    fun createFile(target: Path, content: ByteArray): SecureWorkspaceMutationResult =
        withParentDirectoryDurabilityEvidence {
            createFileExact(target, content, createParents = true)
        }

    fun createFileRequiringExistingParents(target: Path, content: String): SecureWorkspaceMutationResult =
        withParentDirectoryDurabilityEvidence {
            createFileExact(target, strictUtf8Bytes(content), createParents = false)
        }

    fun createFileRequiringExistingParents(target: Path, content: ByteArray): SecureWorkspaceMutationResult =
        withParentDirectoryDurabilityEvidence {
            createFileExact(target, content, createParents = false)
        }

    fun createFileRequiringExistingParents(
        target: Path,
        content: String,
        scratch: ParsedMutationScratchSet,
    ): SecureWorkspaceMutationResult = withParentDirectoryDurabilityEvidence {
        createFileExact(
            target,
            strictUtf8Bytes(content),
            createParents = false,
            scratch = scratch,
        )
    }

    fun createFileRequiringExistingParents(
        target: Path,
        content: ByteArray,
        scratch: ParsedMutationScratchSet,
    ): SecureWorkspaceMutationResult = withParentDirectoryDurabilityEvidence {
        createFileExact(target, content, createParents = false, scratch = scratch)
    }

    fun replaceFile(target: Path, expectedDiskHash: String, content: String): SecureWorkspaceMutationResult =
        withParentDirectoryDurabilityEvidence {
            replaceFileExact(target, expectedDiskHash, strictUtf8Bytes(content))
        }

    fun replaceFile(
        target: Path,
        expectedDiskHash: String,
        content: ByteArray,
        scratch: ParsedMutationScratchSet? = null,
    ): SecureWorkspaceMutationResult = withParentDirectoryDurabilityEvidence {
        replaceFileExact(target, expectedDiskHash, content, scratch)
    }

    fun deleteFile(
        target: Path,
        expectedDiskHash: String,
        scratch: ParsedMutationScratchSet? = null,
    ): SecureWorkspaceMutationResult = withParentDirectoryDurabilityEvidence {
        deleteFileExact(target, expectedDiskHash, scratch)
    }

    fun verifyCommittedFile(
        target: Path,
        expectedContent: String,
        mutation: IdeaWorkspaceMutation,
    ) = verifyCommittedFile(target, strictUtf8Bytes(expectedContent), mutation)

    fun verifyCommittedFile(
        target: Path,
        expectedContent: ByteArray,
        mutation: IdeaWorkspaceMutation,
    ) = verifyCommittedFileState(target, expectedContent.copyOf(), mutation)

    fun readFileBytes(target: Path, mutation: IdeaWorkspaceMutation): ByteArray =
        readFileBytesState(target, mutation)

    fun observeExactFile(
        target: Path,
        mutation: IdeaWorkspaceMutation,
    ): SecureWorkspaceFileObservation = observeExactFileState(target, mutation)

    fun currentFileSha256(target: Path, mutation: IdeaWorkspaceMutation): String =
        FileHashing.sha256(readFileBytes(target, mutation))

    fun verifyCommittedDeletion(target: Path) = verifyCommittedDeletionState(target)

}
