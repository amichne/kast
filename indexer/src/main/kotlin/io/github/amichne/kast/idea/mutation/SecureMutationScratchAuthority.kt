package io.github.amichne.kast.idea.mutation

import com.sun.jna.Native
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.ParsedMutationScratchSet
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import java.nio.file.Path

internal data class SecureMutationScratchNames(
    val quarantineName: String,
    val preparedName: String,
    val preparedCleanupName: String,
    val quarantineCleanupName: String,
    val ownedPaths: Set<Path>,
)

internal fun SecureWorkspaceMutation.requireScratchNames(
    target: Path,
    scratch: ParsedMutationScratchSet?,
    mutation: IdeaWorkspaceMutation,
): SecureMutationScratchNames? {
    if (scratch == null) return null
    val normalizedTarget = requireWorkspaceTarget(target, mutation)
    if (scratch.targetFilePath.toJavaPath() != normalizedTarget) {
        throw UnsafeWorkspaceMutationException(
            message = "Mutation scratch authority does not match the exact target",
            details = failureDetails(normalizedTarget, "scratch-target-mismatch"),
        )
    }
    val ownedPaths = scratch.ownedPaths.map { path ->
        requireWorkspaceTarget(path.toJavaPath(), mutation)
    }
    if (ownedPaths.any { path -> path.parent != normalizedTarget.parent } || ownedPaths.distinct().size != 4) {
        throw UnsafeWorkspaceMutationException(
            message = "Mutation scratch authority is not one unique same-parent role set",
            details = failureDetails(normalizedTarget, "scratch-role-shape"),
        )
    }
    return SecureMutationScratchNames(
        quarantineName = scratch.quarantinePath.toJavaPath().fileName.toString(),
        preparedName = scratch.preparedPath.toJavaPath().fileName.toString(),
        preparedCleanupName = scratch.preparedCleanupPath.toJavaPath().fileName.toString(),
        quarantineCleanupName = scratch.quarantineCleanupPath.toJavaPath().fileName.toString(),
        ownedPaths = ownedPaths.toSet(),
    )
}

internal fun SecureWorkspaceMutation.requireScratchEntriesAbsent(
    parent: NativeDescriptor,
    target: Path,
    scratch: SecureMutationScratchNames?,
    api: PosixFileApi,
    platform: PosixPlatform,
) {
    if (scratch == null) return
    val roleNames = listOf(
        scratch.quarantineName,
        scratch.preparedName,
        scratch.preparedCleanupName,
        scratch.quarantineCleanupName,
    )
    val internalNames = descriptorEntryNames(parent, target, api, platform)
        .filter(::isMutationScratchInternalName)
    if (internalNames.isNotEmpty()) {
        throw ConflictException(
            message = "Mutation scratch preflight found an occupied internal path",
            details = buildMap {
                putAll(failureDetails(target, "scratch-preflight-collision"))
                put("scratchFilePathCount", internalNames.size.toString())
                internalNames.forEachIndexed { index, name ->
                    put("scratchFilePath.$index", target.parent.resolve(name).toString())
                }
            },
        )
    }
    roleNames.forEach { name ->
        val descriptor = api.openat(parent.value, name, platform.readFileFlags, 0)
        if (descriptor >= 0) {
            api.close(descriptor)
            throw ConflictException(
                message = "A predeclared mutation scratch path is already occupied",
                details = failureDetails(target, "scratch-preflight-collision") + mapOf(
                    "scratchFilePath" to target.parent.resolve(name).toString(),
                ),
            )
        }
        val errno = Native.getLastError()
        if (errno != platform.notFoundErrno) {
            throw UnsafeWorkspaceMutationException(
                message = "A predeclared mutation scratch path could not be proven absent",
                details = failureDetails(target, "scratch-preflight-unsafe") + mapOf(
                    "scratchFilePath" to target.parent.resolve(name).toString(),
                    "errno" to errno.toString(),
                ),
            )
        }
    }
}

internal fun SecureWorkspaceMutation.requireScratchBatchAbsent(
    scratchSets: List<ParsedMutationScratchSet>,
) {
    scratchSets.forEach { scratch ->
        val target = requireWorkspaceTarget(
            scratch.targetFilePath.toJavaPath(),
            IdeaWorkspaceMutation.TEXT_EDIT,
        )
        val names = requireNotNull(requireScratchNames(target, scratch, IdeaWorkspaceMutation.TEXT_EDIT))
        withParentDescriptor(target, createParents = false) { parent, _, api, platform ->
            requireScratchEntriesAbsent(parent, target, names, api, platform)
        }
    }
}

internal fun isMutationScratchInternalName(name: String): Boolean =
    name.startsWith(QUARANTINE_PREFIX) ||
        name.startsWith(PREPARED_PREFIX) ||
        name.startsWith(CLEANUP_PREFIX)

internal fun SecureWorkspaceMutationResult.requireOwnedRecoverySubset(
    scratch: SecureMutationScratchNames?,
): SecureWorkspaceMutationResult {
    if (scratch == null || this !is SecureWorkspaceMutationResult.CommittedWithRecovery) return this
    val unknown = recoveryFilePaths.filterNot(scratch.ownedPaths::contains)
    if (unknown.isNotEmpty()) {
        throw UnsafeWorkspaceMutationException(
            message = "Verified mutation retained a path outside its supplied scratch roles",
            details = buildMap {
                put("recoveryFilePathCount", unknown.size.toString())
                unknown.forEachIndexed { index, path -> put("recoveryFilePath.$index", path.toString()) }
            },
        )
    }
    return this
}
