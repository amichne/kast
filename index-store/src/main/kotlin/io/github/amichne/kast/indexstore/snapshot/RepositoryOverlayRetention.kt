package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.api.contract.NormalizedPath
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption

@JvmInline
internal value class ActiveRepositoryOverlayBasePins(val keys: Set<SnapshotKey>)

sealed interface RepositoryOverlayRetentionFailure {
    data class WorkspaceAuthorityInvalid(val path: NormalizedPath) : RepositoryOverlayRetentionFailure

    data class WorkspaceCacheSymlinked(val path: NormalizedPath) : RepositoryOverlayRetentionFailure

    data class WorkspaceEntryInvalid(val path: NormalizedPath) : RepositoryOverlayRetentionFailure

    data class DescriptorInvalid(val path: NormalizedPath) : RepositoryOverlayRetentionFailure

    data class DescriptorMalformed(val path: NormalizedPath) : RepositoryOverlayRetentionFailure

    data class CurrentRepositoryBaseInvalid(
        val descriptor: NormalizedPath,
        val failure: RepositorySnapshotDatabaseFailure,
    ) : RepositoryOverlayRetentionFailure
}

internal sealed interface RepositoryOverlayRetentionResolution {
    data class Resolved(val pins: ActiveRepositoryOverlayBasePins) : RepositoryOverlayRetentionResolution

    data class Rejected(val failure: RepositoryOverlayRetentionFailure) : RepositoryOverlayRetentionResolution
}

private data class ValidatedRepositoryOverlayDescriptor(
    val path: NormalizedPath,
    val manifest: OverlayManifest,
)

private class ValidatedRepositoryWorkspaceDirectory(
    val path: NormalizedPath,
)

private sealed interface RepositoryOverlayDescriptorResolution {
    data class Resolved(
        val descriptor: ValidatedRepositoryOverlayDescriptor,
    ) : RepositoryOverlayDescriptorResolution

    data class Rejected(val failure: RepositoryOverlayRetentionFailure) : RepositoryOverlayDescriptorResolution
}

private sealed interface RepositoryOverlayBaseMembership {
    data object OtherRepository : RepositoryOverlayBaseMembership

    data class CurrentRepository(val database: RepositorySnapshotDatabase) : RepositoryOverlayBaseMembership

    data class Rejected(val failure: RepositoryOverlayRetentionFailure) : RepositoryOverlayBaseMembership
}

internal class RepositoryOverlayRetention(
    private val snapshots: RepositorySnapshotLayout,
) {
    /**
     * Proof transition:
     * `RepositorySnapshotLayout -> RepositoryOverlayRetentionResolution`.
     *
     * A resolved value carries the exact snapshot keys retained by validated
     * active workspace overlays for this repository. Invalid workspace
     * authority, descriptors, or current-repository bases are finite
     * [RepositoryOverlayRetentionFailure] data. Raw paths remain confined to
     * this filesystem boundary.
     */
    fun activeBasePins(): RepositoryOverlayRetentionResolution = when (val registry = snapshots.registry) {
        RepositoryRegistryLayout.Standalone -> RepositoryOverlayRetentionResolution.Resolved(
            ActiveRepositoryOverlayBasePins(emptySet()),
        )
        is RepositoryRegistryLayout.Keyed -> readKeyedRegistry(registry)
    }

    private fun readKeyedRegistry(
        registry: RepositoryRegistryLayout.Keyed,
    ): RepositoryOverlayRetentionResolution {
        val workspaces = registry.workspacesDirectory.toJavaPath()
        if (!Files.exists(workspaces, LinkOption.NOFOLLOW_LINKS)) {
            return resolved(emptySet())
        }
        if (!Files.isDirectory(workspaces, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(workspaces)) {
            return rejected(
                RepositoryOverlayRetentionFailure.WorkspaceAuthorityInvalid(registry.workspacesDirectory),
            )
        }
        val pins = mutableSetOf<SnapshotKey>()
        val workspaceDirectories = Files.list(workspaces).use { entries -> entries.toList() }
        for (workspace in workspaceDirectories) {
            if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(workspace)) {
                return rejected(
                    RepositoryOverlayRetentionFailure.WorkspaceEntryInvalid(NormalizedPath.ofAbsolute(workspace)),
                )
            }
            val workspaceDirectory = ValidatedRepositoryWorkspaceDirectory(NormalizedPath.ofAbsolute(workspace))
            val descriptorPath = when (val resolution = descriptorInWorkspace(workspaceDirectory)) {
                is RepositoryOverlayDescriptorPathResolution.Available -> resolution.path
                RepositoryOverlayDescriptorPathResolution.Absent -> continue
                is RepositoryOverlayDescriptorPathResolution.Rejected -> return rejected(resolution.failure)
            }
            val descriptor = when (val resolution = readDescriptor(descriptorPath)) {
                is RepositoryOverlayDescriptorResolution.Resolved -> resolution.descriptor
                is RepositoryOverlayDescriptorResolution.Rejected -> return rejected(resolution.failure)
            }
            when (val membership = repositoryMembership(descriptor)) {
                RepositoryOverlayBaseMembership.OtherRepository -> Unit
                is RepositoryOverlayBaseMembership.CurrentRepository -> pins += membership.database.key
                is RepositoryOverlayBaseMembership.Rejected -> return rejected(membership.failure)
            }
        }
        return resolved(pins)
    }

    /**
     * Proof transition:
     * `ValidatedRepositoryWorkspaceDirectory -> RepositoryOverlayDescriptorPathResolution`.
     *
     * Derives an explicit absent descriptor or a normalized descriptor path;
     * a symlinked cache rejects retention with finite failure data. Raw paths
     * are extracted only for filesystem inspection.
     */
    private fun descriptorInWorkspace(
        workspace: ValidatedRepositoryWorkspaceDirectory,
    ): RepositoryOverlayDescriptorPathResolution {
        val cache = workspace.path.toJavaPath().resolve(CACHE_DIRECTORY)
        if (Files.exists(cache, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cache)) {
            return RepositoryOverlayDescriptorPathResolution.Rejected(
                RepositoryOverlayRetentionFailure.WorkspaceCacheSymlinked(NormalizedPath.ofAbsolute(cache)),
            )
        }
        val descriptor = cache.resolve(OVERLAY_FILE)
        return if (Files.exists(descriptor, LinkOption.NOFOLLOW_LINKS)) {
            RepositoryOverlayDescriptorPathResolution.Available(NormalizedPath.ofAbsolute(descriptor))
        } else {
            RepositoryOverlayDescriptorPathResolution.Absent
        }
    }

    /**
     * Proof transition: `NormalizedPath -> RepositoryOverlayDescriptorResolution`.
     *
     * A resolved descriptor is a regular non-symlink file containing a
     * schema-valid [OverlayManifest]. Rejection is finite
     * [RepositoryOverlayRetentionFailure] data. The input path is used only at
     * this filesystem and serialization boundary.
     */
    private fun readDescriptor(path: NormalizedPath): RepositoryOverlayDescriptorResolution {
        val normalized = path
        val file = path.toJavaPath()
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            return rejectedDescriptor(RepositoryOverlayRetentionFailure.DescriptorInvalid(normalized))
        }
        val manifest = runCatching { JSON.decodeFromString<OverlayManifest>(Files.readString(file)) }
            .getOrElse {
                return rejectedDescriptor(RepositoryOverlayRetentionFailure.DescriptorMalformed(normalized))
            }
        return RepositoryOverlayDescriptorResolution.Resolved(
            ValidatedRepositoryOverlayDescriptor(normalized, manifest),
        )
    }

    /**
     * Proof transition:
     * `ValidatedRepositoryOverlayDescriptor -> RepositoryOverlayBaseMembership`.
     *
     * A current-repository result carries a fully validated snapshot database;
     * another repository and rejection are explicit closed states.
     */
    private fun repositoryMembership(
        descriptor: ValidatedRepositoryOverlayDescriptor,
    ): RepositoryOverlayBaseMembership = when (val resolution = snapshots.resolveOverlayBase(descriptor.manifest)) {
        RepositoryOverlayBaseResolution.OtherRepository -> RepositoryOverlayBaseMembership.OtherRepository
        is RepositoryOverlayBaseResolution.CurrentRepository ->
            RepositoryOverlayBaseMembership.CurrentRepository(resolution.database)
        is RepositoryOverlayBaseResolution.Rejected -> RepositoryOverlayBaseMembership.Rejected(
            RepositoryOverlayRetentionFailure.CurrentRepositoryBaseInvalid(
                descriptor.path,
                resolution.failure,
            ),
        )
    }

    private fun resolved(keys: Set<SnapshotKey>) = RepositoryOverlayRetentionResolution.Resolved(
        ActiveRepositoryOverlayBasePins(keys),
    )

    private fun rejected(failure: RepositoryOverlayRetentionFailure) =
        RepositoryOverlayRetentionResolution.Rejected(failure)

    private fun rejectedDescriptor(failure: RepositoryOverlayRetentionFailure) =
        RepositoryOverlayDescriptorResolution.Rejected(failure)

    private sealed interface RepositoryOverlayDescriptorPathResolution {
        data object Absent : RepositoryOverlayDescriptorPathResolution

        data class Available(val path: NormalizedPath) : RepositoryOverlayDescriptorPathResolution

        data class Rejected(val failure: RepositoryOverlayRetentionFailure) :
            RepositoryOverlayDescriptorPathResolution
    }

    private companion object {
        const val CACHE_DIRECTORY = "cache"
        const val OVERLAY_FILE = "repository-overlay.json"
        val JSON = Json { ignoreUnknownKeys = false }
    }
}
