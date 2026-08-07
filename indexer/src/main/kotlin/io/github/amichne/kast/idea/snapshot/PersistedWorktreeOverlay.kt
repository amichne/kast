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

    data class OtherRepository(
        val authorityChange: PersistedRepositoryAuthorityChange,
    ) : PersistedWorktreeOverlayResolution

    data class Rejected(val failure: PersistedWorktreeOverlayFailure) : PersistedWorktreeOverlayResolution
}

@JvmInline
internal value class WorkspaceSourceIndexDatabase private constructor(val path: NormalizedPath) {
    companion object {
        /**
         * Derivation transition:
         * `NormalizedPath(workspace database) -> WorkspaceSourceIndexDatabase`.
         *
         * Refines the workspace identity's normalized database path into the
         * authority whose complete SQLite file family can be revoked. The
         * result need not retain the broader workspace identity input.
         */
        fun fromWorkspaceIdentity(path: NormalizedPath): WorkspaceSourceIndexDatabase =
            WorkspaceSourceIndexDatabase(path)
    }
}

internal class RevokedPersistedRepositoryAuthority internal constructor(
    val absence: WorktreeOverlayAbsence.RepositoryAuthorityChanged,
)

internal class FullIndexOverlayAuthority private constructor(
    val absence: WorktreeOverlayAbsence,
) {
    companion object {
        /**
         * Proof transition:
         * `(WorktreeOverlayDescriptor, WorktreeOverlayAbsence) -> FullIndexOverlayAuthority`.
         *
         * Revokes any previously published overlay descriptor before deriving
         * the constrained authority to open a standalone full index. The
         * result retains the reason, not the descriptor input.
         */
        fun revoke(
            descriptor: WorktreeOverlayDescriptor,
            absence: WorktreeOverlayAbsence,
        ): FullIndexOverlayAuthority {
            Files.deleteIfExists(descriptor.path.toJavaPath())
            return FullIndexOverlayAuthority(absence)
        }

        /**
         * Proof transition:
         * `RevokedPersistedRepositoryAuthority -> FullIndexOverlayAuthority`.
         *
         * Refines proof that the previous repository's local evidence has
         * been removed into authority to open a standalone full index. The
         * result retains only the derived absence reason, not the input proof.
         */
        fun from(revoked: RevokedPersistedRepositoryAuthority): FullIndexOverlayAuthority =
            FullIndexOverlayAuthority(revoked.absence)
    }
}

internal class PersistedRepositoryAuthorityChange internal constructor(
    private val database: WorkspaceSourceIndexDatabase,
    private val descriptor: WorktreeOverlayDescriptor,
) {
    /**
     * Proof transition:
     * `PersistedRepositoryAuthorityChange -> RevokedPersistedRepositoryAuthority`.
     *
     * Deletes the previous repository's complete overlay-local SQLite family
     * before deleting its descriptor. A successful result proves that no
     * source-stage ownership or provenance can survive into the replacement
     * repository; it is a stronger derivation and does not retain the input.
     */
    fun revoke(): RevokedPersistedRepositoryAuthority {
        repositoryAuthorityArtifacts(database, descriptor).forEach { artifact ->
            Files.deleteIfExists(artifact.path.toJavaPath())
        }
        return RevokedPersistedRepositoryAuthority(WorktreeOverlayAbsence.RepositoryAuthorityChanged)
    }
}

internal class WorktreeOverlayDescriptor private constructor(
    val path: NormalizedPath,
    private val database: WorkspaceSourceIndexDatabase,
) {
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
                PersistedWorktreeOverlayResolution.OtherRepository(
                    PersistedRepositoryAuthorityChange(database, this),
                )
            is RepositoryOverlayBaseResolution.Rejected -> PersistedWorktreeOverlayResolution.Rejected(
                PersistedWorktreeOverlayFailure.CurrentRepositoryBaseRejected(path, resolution.failure),
            )
        }
    }

    companion object {
        /**
         * Derivation transition:
         * `WorkspaceSourceIndexDatabase -> WorktreeOverlayDescriptor`.
         *
         * Derives the one descriptor authority adjacent to the normalized
         * workspace database; no raw path is retained outside the constrained
         * output type.
         */
        fun beside(workspaceDatabase: WorkspaceSourceIndexDatabase): WorktreeOverlayDescriptor =
            WorktreeOverlayDescriptor(
                NormalizedPath.ofAbsolute(workspaceDatabase.path.toJavaPath().resolveSibling(OVERLAY_FILE)),
                workspaceDatabase,
            )

        private const val OVERLAY_FILE = "repository-overlay.json"
        private val JSON = Json { ignoreUnknownKeys = false }
    }
}

private sealed interface PersistedRepositoryAuthorityArtifact {
    val path: NormalizedPath

    data class RollbackJournal(override val path: NormalizedPath) : PersistedRepositoryAuthorityArtifact

    data class WriteAheadLog(override val path: NormalizedPath) : PersistedRepositoryAuthorityArtifact

    data class SharedMemory(override val path: NormalizedPath) : PersistedRepositoryAuthorityArtifact

    data class Database(override val path: NormalizedPath) : PersistedRepositoryAuthorityArtifact

    data class OverlayDescriptor(override val path: NormalizedPath) : PersistedRepositoryAuthorityArtifact
}

private fun repositoryAuthorityArtifacts(
    database: WorkspaceSourceIndexDatabase,
    descriptor: WorktreeOverlayDescriptor,
): List<PersistedRepositoryAuthorityArtifact> {
    val databasePath = database.path.toJavaPath()
    val databaseName = databasePath.fileName
    return listOf(
        PersistedRepositoryAuthorityArtifact.RollbackJournal(
            NormalizedPath.ofAbsolute(databasePath.resolveSibling("$databaseName-journal")),
        ),
        PersistedRepositoryAuthorityArtifact.WriteAheadLog(
            NormalizedPath.ofAbsolute(databasePath.resolveSibling("$databaseName-wal")),
        ),
        PersistedRepositoryAuthorityArtifact.SharedMemory(
            NormalizedPath.ofAbsolute(databasePath.resolveSibling("$databaseName-shm")),
        ),
        PersistedRepositoryAuthorityArtifact.Database(database.path),
        PersistedRepositoryAuthorityArtifact.OverlayDescriptor(descriptor.path),
    )
}
