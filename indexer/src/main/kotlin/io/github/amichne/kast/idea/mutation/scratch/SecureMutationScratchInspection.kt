package io.github.amichne.kast.idea.mutation

import com.sun.jna.Native
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.result.MutationScratchInspectResult
import io.github.amichne.kast.api.contract.result.MutationScratchObservation
import io.github.amichne.kast.api.contract.result.MutationScratchOwnership
import io.github.amichne.kast.api.contract.result.MutationScratchRole
import io.github.amichne.kast.api.contract.result.MutationScratchState
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationScratchInspectQuery
import io.github.amichne.kast.api.validation.ParsedMutationScratchSet
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import java.nio.file.Path

internal data class SecureScratchEntryObservation(
    val state: MutationScratchState,
    val sha256: ExactFileImageSha256? = null,
    val identity: NativeFileIdentity? = null,
    val mode: NativeFileMode? = null,
)

internal fun SecureWorkspaceMutation.inspectMutationScratch(
    query: ParsedMutationScratchInspectQuery,
): MutationScratchInspectResult {
    val parents = query.workspaceRelativeParentPaths.map { relative ->
        relative.resolveUnder(normalizedWorkspaceRoot).also { parent ->
            if (!parent.startsWith(normalizedWorkspaceRoot)) {
                throw ValidationException("Mutation scratch parent escaped the exact workspace root")
            }
        }
    }
    val parentSet = parents.toSet()
    val ownedRoles = buildMap<Path, MutationScratchRole> {
        query.ownedScratchSets.forEach { scratch ->
            scratch.requireContainedScratchSet(parentSet).forEach { (path, role) ->
                if (put(path, role) != null) {
                    throw ValidationException("Owned mutation scratch role paths must be unique across all sets")
                }
            }
        }
    }
    val observations = mutableListOf<MutationScratchObservation>()
    parents.forEach { parent ->
        val ownedInParent = ownedRoles.filterKeys { path -> path.parent == parent }
        withParentDescriptor(parent.resolve(INSPECTION_ANCHOR), createParents = false) { descriptor, _, api, platform ->
            val discovered = descriptorEntryNames(descriptor, parent, api, platform)
                .filter(::isMutationScratchInternalName)
                .map(parent::resolve)
                .toSet()
            (ownedInParent.keys + discovered).forEach { path ->
                val role = ownedRoles[path]
                val secure = observeScratchEntry(
                    parent = descriptor,
                    name = path.fileName.toString(),
                    target = parent.resolve(INSPECTION_ANCHOR),
                    api = api,
                    platform = platform,
                )
                val owned = role != null
                observations += MutationScratchObservation(
                    filePath = path.toString(),
                    ownership = if (owned) MutationScratchOwnership.OWNED else MutationScratchOwnership.UNOWNED,
                    role = role ?: MutationScratchRole.UNOWNED_INTERNAL,
                    state = if (!owned && secure.state == MutationScratchState.ABSENT) {
                        MutationScratchState.UNSAFE
                    } else {
                        secure.state
                    },
                    sha256 = secure.sha256,
                )
            }
        }
    }
    return MutationScratchInspectResult(
        mutationAttemptId = query.mutationAttemptId,
        observations = observations.sortedBy(MutationScratchObservation::filePath),
    )
}

internal fun SecureWorkspaceMutation.observeScratchEntry(
    parent: NativeDescriptor,
    name: String,
    target: Path,
    api: PosixFileApi,
    platform: PosixPlatform,
): SecureScratchEntryObservation {
    val descriptorValue = api.openat(parent.value, name, platform.readFileFlags, 0)
    if (descriptorValue < 0) {
        return if (Native.getLastError() == platform.notFoundErrno) {
            SecureScratchEntryObservation(MutationScratchState.ABSENT)
        } else {
            SecureScratchEntryObservation(MutationScratchState.UNSAFE)
        }
    }
    return NativeDescriptor(api, descriptorValue).use { descriptor ->
        val initialStatus = runCatching {
            descriptorStatus(api, platform, descriptor.value, target)
        }.getOrElse { return SecureScratchEntryObservation(MutationScratchState.UNSAFE) }
        if (initialStatus.mode.fileType != NativeFileType.REGULAR) {
            return SecureScratchEntryObservation(MutationScratchState.UNSAFE)
        }
        val bytes = runCatching { readFullyBytes(api, descriptor.value, target) }
            .getOrElse { return SecureScratchEntryObservation(MutationScratchState.UNSAFE) }
        val currentDescriptor = api.openat(parent.value, name, platform.readFileFlags, 0)
        if (currentDescriptor < 0) return SecureScratchEntryObservation(MutationScratchState.UNSAFE)
        val stillSame = NativeDescriptor(api, currentDescriptor).use { current ->
            runCatching {
                descriptorStatus(api, platform, current.value, target).let { status ->
                    status.mode.fileType == NativeFileType.REGULAR && status.identity == initialStatus.identity
                }
            }.getOrDefault(false)
        }
        if (!stillSame) return SecureScratchEntryObservation(MutationScratchState.UNSAFE)
        SecureScratchEntryObservation(
            state = MutationScratchState.PRESENT,
            sha256 = ExactFileImageSha256(FileHashing.sha256(bytes)),
            identity = initialStatus.identity,
            mode = initialStatus.mode,
        )
    }
}

internal fun SecureWorkspaceMutation.descriptorEntryNames(
    parent: NativeDescriptor,
    target: Path,
    api: PosixFileApi,
    platform: PosixPlatform,
): List<String> {
    val duplicate = api.openat(parent.value, ".", platform.directoryFlags, 0)
    if (duplicate < 0) throw nativeFailure("openat-enumeration-parent", target, ".", Native.getLastError())
    val directory = api.fdopendir(duplicate)
    if (directory == null) {
        val errno = Native.getLastError()
        api.close(duplicate)
        throw nativeFailure("fdopendir", target, ".", errno)
    }
    return try {
        buildList {
            while (true) {
                Native.setLastError(0)
                val entry = api.readdir(directory)
                if (entry == null) {
                    val errno = Native.getLastError()
                    if (errno != 0) throw nativeFailure("readdir", target, ".", errno)
                    break
                }
                val name = runCatching { entry.getString(platform.directoryEntryNameOffset) }
                    .getOrElse { failure ->
                        throw UnsafeWorkspaceMutationException(
                            message = "Descriptor-secure mutation scratch enumeration found an unreadable name",
                            details = failureDetails(target, "readdir-name") + mapOf(
                                "cause" to (failure.message ?: failure::class.java.simpleName),
                            ),
                        )
                    }
                if (name != "." && name != "..") add(name)
            }
        }.sorted()
    } finally {
        api.closedir(directory)
    }
}

private fun ParsedMutationScratchSet.requireContainedScratchSet(
    inspectedParents: Set<Path>,
): Map<Path, MutationScratchRole> {
    val target = targetFilePath.toJavaPath()
    val roles = linkedMapOf(
        quarantinePath.toJavaPath() to MutationScratchRole.QUARANTINE,
        preparedPath.toJavaPath() to MutationScratchRole.PREPARED,
        preparedCleanupPath.toJavaPath() to MutationScratchRole.PREPARED_CLEANUP,
        quarantineCleanupPath.toJavaPath() to MutationScratchRole.QUARANTINE_CLEANUP,
    )
    if (target.parent !in inspectedParents || roles.keys.any { path -> path.parent != target.parent }) {
        throw ValidationException(
            "Every owned mutation scratch set parent must be included in workspaceRelativeParentPaths",
        )
    }
    return roles
}

private const val INSPECTION_ANCHOR = ".kast-scratch-inspection-anchor"
