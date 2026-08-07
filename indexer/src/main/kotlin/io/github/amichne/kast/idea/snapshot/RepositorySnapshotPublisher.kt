package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.SnapshotCreationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.SnapshotExportTarget
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal data class RepositorySnapshotContext(
    val workspaceRoot: NormalizedPath,
    val repositoryDirectory: NormalizedPath,
    val buildClasspathFingerprint: BuildClasspathFingerprint,
    val producerVersion: ProducerVersion,
)

sealed interface RepositorySnapshotPublication {
    /**
     * Proof transition:
     * `(RepositorySnapshotPublication, SqliteSourceIndexStore)`
     * `-> RepositorySnapshotPublicationOutcome`.
     *
     * Consumes the capability captured for one reconciliation and returns a
     * closed outcome. An eligible capability exports only while the current
     * Git tree still equals its retained [CommittedGitTree]; movement remains
     * finite [RepositorySnapshotPublicationSkip] data. Raw Git and SQLite
     * extraction is confined to their process and storage boundaries.
     */
    fun publish(store: SqliteSourceIndexStore): RepositorySnapshotPublicationOutcome

    data object Unmanaged : RepositorySnapshotPublication {
        override fun publish(store: SqliteSourceIndexStore) =
            RepositorySnapshotPublicationOutcome.UnmanagedWorkspace
    }

    data object Suppressed : RepositorySnapshotPublication {
        override fun publish(store: SqliteSourceIndexStore) =
            RepositorySnapshotPublicationOutcome.SuppressedForWorktreeOverlay
    }
}

internal sealed interface RepositorySnapshotPublicationAuthority {
    /**
     * Proof transition:
     * `RepositorySnapshotPublicationAuthority -> RepositorySnapshotPublication`.
     *
     * Derives one reconciliation-scoped publication capability. Eligible
     * repository authority captures an exact clean [CommittedGitTree]; an
     * expected Git failure is retained by the returned capability and later
     * becomes finite skip data. Raw Git output is extracted only by
     * [CommittedGitTreeResolver].
     */
    fun capture(): RepositorySnapshotPublication

    data object Suppressed : RepositorySnapshotPublicationAuthority {
        override fun capture(): RepositorySnapshotPublication = RepositorySnapshotPublication.Suppressed
    }

    class Eligible(
        private val context: RepositorySnapshotContext,
    ) : RepositorySnapshotPublicationAuthority {
        override fun capture(): RepositorySnapshotPublication = when (
            val resolution = CommittedGitTreeResolver.resolve(context.workspaceRoot)
        ) {
            is CommittedGitTreeResolution.Resolved ->
                EligibleRepositorySnapshotPublication(context, resolution.tree)
            is CommittedGitTreeResolution.Unavailable ->
                UnavailableRepositorySnapshotPublication(resolution.failure)
        }
    }
}

private class UnavailableRepositorySnapshotPublication(
    private val failure: CommittedGitTreeFailure,
) : RepositorySnapshotPublication {
    override fun publish(store: SqliteSourceIndexStore) =
        RepositorySnapshotPublicationOutcome.Skipped(
            RepositorySnapshotPublicationSkip.CommittedTreeUnavailable(failure),
        )
}

/**
 * Proof transition:
 * `(RepositorySnapshotContext, CommittedGitTree)`
 * `-> EligibleRepositorySnapshotPublication`.
 *
 * Retains the exact clean committed tree captured with one workspace
 * reconciliation. The resulting capability cannot derive a replacement tree
 * when the completed database is published.
 */
private class EligibleRepositorySnapshotPublication(
    private val context: RepositorySnapshotContext,
    private val sourceTree: CommittedGitTree,
) : RepositorySnapshotPublication {
    override fun publish(store: SqliteSourceIndexStore): RepositorySnapshotPublicationOutcome {
        val branch = RepositorySnapshotCoordinator.currentBranch(context.workspaceRoot)
        if (branch != RepositoryBranch.Main) {
            return RepositorySnapshotPublicationOutcome.Skipped(
                RepositorySnapshotPublicationSkip.BranchNotMain(branch),
            )
        }
        val before = CommittedGitTreeResolver.resolve(context.workspaceRoot)
        if (before != CommittedGitTreeResolution.Resolved(sourceTree)) {
            return RepositorySnapshotPublicationOutcome.Skipped(
                RepositorySnapshotPublicationSkip.CommittedTreeMoved(sourceTree, before),
            )
        }
        val key = SnapshotKey(
            treeOid = sourceTree.treeOid,
            buildClasspathFingerprint = context.buildClasspathFingerprint,
            indexSchema = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
            producerVersion = context.producerVersion,
        )
        return SnapshotExportTarget.allocate(context.repositoryDirectory).use { target ->
            val evidence = store.exportSnapshotDatabase(
                target,
                sourceTree.treeOid,
                context.producerVersion,
            )
            val after = CommittedGitTreeResolver.resolve(context.workspaceRoot)
            if (after != CommittedGitTreeResolution.Resolved(sourceTree)) {
                return RepositorySnapshotPublicationOutcome.Skipped(
                    RepositorySnapshotPublicationSkip.CommittedTreeMoved(sourceTree, after),
                )
            }
            RepositorySnapshotPublicationOutcome.Completed(
                RepositorySnapshotStore(context.repositoryDirectory.toJavaPath()).publishMain(
                    manifest = SnapshotManifest(
                        key,
                        sourceTree.files,
                        SnapshotCreationEpochMillis.fromClock(System.currentTimeMillis()),
                    ),
                    sourceDatabase = target.database,
                    evidence = evidence,
                ),
            )
        }
    }
}
