package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.RepositoryOverlayBaseResolution
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseFailure
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption

sealed interface PersistedWorktreeOverlayFailure {
    data class DescriptorInvalid(val path: NormalizedPath) : PersistedWorktreeOverlayFailure

    data class DescriptorMalformed(val path: NormalizedPath) : PersistedWorktreeOverlayFailure

    data class CurrentRepositoryBaseRejected(
        val path: NormalizedPath,
        val failure: RepositorySnapshotDatabaseFailure,
    ) : PersistedWorktreeOverlayFailure
}

internal sealed interface PersistedWorktreeOverlayResolution {
    data object Absent : PersistedWorktreeOverlayResolution

    data object CurrentRepository : PersistedWorktreeOverlayResolution

    data object OtherRepository : PersistedWorktreeOverlayResolution

    data class Rejected(val failure: PersistedWorktreeOverlayFailure) : PersistedWorktreeOverlayResolution
}

@JvmInline
internal value class WorktreeOverlayDescriptor private constructor(val path: NormalizedPath) {
    /**
     * Proof transition:
     * `(WorktreeOverlayDescriptor, RepositorySnapshotStore)`
     * `-> PersistedWorktreeOverlayResolution`.
     *
     * Refines persisted descriptor bytes into explicit absence, exact current
     * repository authority, authority belonging to another repository, or a
     * finite rejection. Raw path and JSON extraction remain confined to this
     * filesystem boundary.
     */
    fun resolve(snapshots: RepositorySnapshotStore): PersistedWorktreeOverlayResolution {
        val descriptor = path.toJavaPath()
        if (!Files.exists(descriptor, LinkOption.NOFOLLOW_LINKS)) {
            return PersistedWorktreeOverlayResolution.Absent
        }
        if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(descriptor)) {
            return PersistedWorktreeOverlayResolution.Rejected(
                PersistedWorktreeOverlayFailure.DescriptorInvalid(path),
            )
        }
        val manifest = runCatching { JSON.decodeFromString<OverlayManifest>(Files.readString(descriptor)) }
            .getOrElse {
                return PersistedWorktreeOverlayResolution.Rejected(
                    PersistedWorktreeOverlayFailure.DescriptorMalformed(path),
                )
            }
        return when (val resolution = snapshots.resolveOverlayBase(manifest)) {
            is RepositoryOverlayBaseResolution.CurrentRepository ->
                PersistedWorktreeOverlayResolution.CurrentRepository
            RepositoryOverlayBaseResolution.OtherRepository ->
                PersistedWorktreeOverlayResolution.OtherRepository
            is RepositoryOverlayBaseResolution.Rejected -> PersistedWorktreeOverlayResolution.Rejected(
                PersistedWorktreeOverlayFailure.CurrentRepositoryBaseRejected(path, resolution.failure),
            )
        }
    }

    companion object {
        /**
         * Derivation transition:
         * `NormalizedPath(workspace database) -> WorktreeOverlayDescriptor`.
         *
         * Derives the one descriptor authority adjacent to the normalized
         * workspace database; no raw path is retained outside the constrained
         * output type.
         */
        fun beside(workspaceDatabase: NormalizedPath): WorktreeOverlayDescriptor = WorktreeOverlayDescriptor(
            NormalizedPath.ofAbsolute(workspaceDatabase.toJavaPath().resolveSibling(OVERLAY_FILE)),
        )

        private const val OVERLAY_FILE = "repository-overlay.json"
        private val JSON = Json { ignoreUnknownKeys = false }
    }
}
