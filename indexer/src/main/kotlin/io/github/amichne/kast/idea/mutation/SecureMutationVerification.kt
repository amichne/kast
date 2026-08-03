package io.github.amichne.kast.idea.mutation

import com.sun.jna.Native
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import java.nio.file.Path

internal fun SecureWorkspaceMutation.verifyCommittedFileState(
    target: Path,
    expectedContent: ByteArray,
    mutation: IdeaWorkspaceMutation,
) {
    val normalizedTarget = requireWorkspaceTarget(target, mutation)
    withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
        val descriptorValue = api.openat(parent.value, fileName, platform.readFileFlags, 0)
        if (descriptorValue < 0) {
            throw nativeFailure(
                operation = "openat-verify-committed-file",
                target = normalizedTarget,
                component = fileName,
                errno = Native.getLastError(),
            )
        }
        NativeDescriptor(api, descriptorValue).use { descriptor ->
            val status = descriptorStatus(api, platform, descriptor.value, normalizedTarget)
            if (status.mode.fileType != NativeFileType.REGULAR) {
                throw UnsafeWorkspaceMutationException(
                    message = "Secure post-commit verification requires a regular file target",
                    details = failureDetails(normalizedTarget, "reject-non-regular-verification-target") + mapOf(
                        "fileType" to status.mode.fileType.name,
                        "fileMode" to status.mode.bits.toString(8),
                    ),
                )
            }
            val actualContent = readFullyBytes(api, descriptor.value, normalizedTarget)
            if (!actualContent.contentEquals(expectedContent)) {
                throw ConflictException(
                    message = "Secure post-commit content verification failed",
                    details = mapOf(
                        "filePath" to normalizedTarget.toString(),
                        "mutation" to mutation.wireName,
                        "expectedHash" to FileHashing.sha256(expectedContent),
                        "actualHash" to FileHashing.sha256(actualContent),
                    ),
                )
            }
        }
    }
}

internal fun SecureWorkspaceMutation.readFileBytesState(
    target: Path,
    mutation: IdeaWorkspaceMutation,
): ByteArray {
    val normalizedTarget = requireWorkspaceTarget(target, mutation)
    return withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
        val descriptorValue = api.openat(parent.value, fileName, platform.readFileFlags, 0)
        if (descriptorValue < 0) {
            val errno = Native.getLastError()
            if (errno == platform.notFoundErrno) {
                throw NotFoundException(
                    message = "The requested file does not exist",
                    details = mapOf("filePath" to normalizedTarget.toString()),
                )
            }
            throw nativeFailure(
                operation = "openat-read-file-bytes",
                target = normalizedTarget,
                component = fileName,
                errno = errno,
            )
        }
        NativeDescriptor(api, descriptorValue).use { descriptor ->
            val status = descriptorStatus(api, platform, descriptor.value, normalizedTarget)
            if (status.mode.fileType != NativeFileType.REGULAR) {
                throw UnsafeWorkspaceMutationException(
                    message = "Secure byte reads require a regular file target",
                    details = failureDetails(normalizedTarget, "reject-non-regular-read-target") + mapOf(
                        "fileType" to status.mode.fileType.name,
                        "fileMode" to status.mode.bits.toString(8),
                    ),
                )
            }
            readFullyBytes(api, descriptor.value, normalizedTarget)
        }
    }
}

internal fun SecureWorkspaceMutation.observeExactFileState(
    target: Path,
    mutation: IdeaWorkspaceMutation,
): SecureWorkspaceFileObservation {
    val normalizedTarget = requireWorkspaceTarget(target, mutation)
    return withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
        val descriptorValue = api.openat(parent.value, fileName, platform.readFileFlags, 0)
        if (descriptorValue < 0) {
            val errno = Native.getLastError()
            if (errno == platform.notFoundErrno) {
                return@withParentDescriptor SecureWorkspaceFileObservation.Absent
            }
            throw nativeFailure(
                operation = "openat-observe-exact-file",
                target = normalizedTarget,
                component = fileName,
                errno = errno,
            )
        }
        NativeDescriptor(api, descriptorValue).use { descriptor ->
            val status = descriptorStatus(api, platform, descriptor.value, normalizedTarget)
            if (status.mode.fileType != NativeFileType.REGULAR) {
                throw UnsafeWorkspaceMutationException(
                    message = "Secure exact observation requires a regular file target",
                    details = failureDetails(normalizedTarget, "reject-non-regular-observation-target") + mapOf(
                        "fileType" to status.mode.fileType.name,
                        "fileMode" to status.mode.bits.toString(8),
                    ),
                )
            }
            SecureWorkspaceFileObservation.Present.of(
                readFullyBytes(api, descriptor.value, normalizedTarget),
            )
        }
    }
}

internal fun SecureWorkspaceMutation.verifyCommittedDeletionState(target: Path) {
    val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.DELETE_FILE)
    withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
        val descriptorValue = api.openat(parent.value, fileName, platform.readFileFlags, 0)
        if (descriptorValue < 0) {
            val errno = Native.getLastError()
            if (errno == platform.notFoundErrno) return@withParentDescriptor
            throw nativeFailure(
                operation = "openat-verify-committed-deletion",
                target = normalizedTarget,
                component = fileName,
                errno = errno,
            )
        }
        NativeDescriptor(api, descriptorValue).close()
        throw ValidationException(
            message = "Secure post-commit deletion verification found a final entry",
            details = mapOf(
                "filePath" to normalizedTarget.toString(),
                "mutation" to IdeaWorkspaceMutation.DELETE_FILE.wireName,
            ),
        )
    }
}
