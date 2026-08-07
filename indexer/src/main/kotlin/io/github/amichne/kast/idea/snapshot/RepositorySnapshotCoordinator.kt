package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.api.client.ReadOnlyGitCommand
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseFailure
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseResolution
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotInventoryResolution
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotSelector
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.RepositoryContentShardFailure
import io.github.amichne.kast.indexstore.snapshot.RepositoryContentShardPayload
import io.github.amichne.kast.indexstore.snapshot.RepositoryContentShardPayloadFailure
import io.github.amichne.kast.indexstore.snapshot.RepositoryContentShardPayloadResolution
import io.github.amichne.kast.indexstore.snapshot.RepositoryContentShardPublicationResult
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotMetadataFailure
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
import io.github.amichne.kast.indexstore.snapshot.SnapshotCreationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.snapshot.SnapshotPublicationResult
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotSelection
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

sealed interface WorktreeOverlaySeed {
    data class None(val reason: WorktreeOverlayAbsence) : WorktreeOverlaySeed

    data class Prepared(val manifest: OverlayManifest) : WorktreeOverlaySeed
}

sealed interface WorktreeOverlayAbsence {
    data object UnmanagedWorkspace : WorktreeOverlayAbsence

    data object ExistingWorkspaceDatabase : WorktreeOverlayAbsence

    data class CommittedTreeUnavailable(val failure: CommittedGitTreeFailure) : WorktreeOverlayAbsence

    data object NoCompatibleRepositorySnapshot : WorktreeOverlayAbsence

    data class BlobUnavailable(val oid: GitObjectId) : WorktreeOverlayAbsence

}

sealed interface RepositorySnapshotPreparationFailure {
    data class SnapshotMetadataRejected(
        val failure: RepositorySnapshotMetadataFailure,
    ) : RepositorySnapshotPreparationFailure

    data class SnapshotDatabaseRejected(
        val failure: RepositorySnapshotDatabaseFailure,
    ) : RepositorySnapshotPreparationFailure

    data class BlobPayloadRejected(
        val oid: GitObjectId,
        val failure: RepositoryContentShardPayloadFailure,
    ) : RepositorySnapshotPreparationFailure

    data class ShardPublicationRejected(
        val oid: GitObjectId,
        val failure: RepositoryContentShardFailure,
    ) : RepositorySnapshotPreparationFailure
}

sealed interface RepositorySnapshotPreparationResolution {
    data class Resolved(
        val preparation: RepositorySnapshotPreparation,
    ) : RepositorySnapshotPreparationResolution

    data class Rejected(
        val failure: RepositorySnapshotPreparationFailure,
    ) : RepositorySnapshotPreparationResolution
}

class RepositorySnapshotPreparationException(
    val failure: RepositorySnapshotPreparationFailure,
) : IllegalStateException(failure.toString())

sealed interface RepositorySnapshotPublicationOutcome {
    data object UnmanagedWorkspace : RepositorySnapshotPublicationOutcome

    data object SuppressedForWorktreeOverlay : RepositorySnapshotPublicationOutcome

    data class Skipped(val reason: RepositorySnapshotPublicationSkip) : RepositorySnapshotPublicationOutcome

    data class Completed(val result: SnapshotPublicationResult) : RepositorySnapshotPublicationOutcome
}

sealed interface RepositorySnapshotPublicationSkip {
    data class BranchNotMain(val branch: RepositoryBranch) : RepositorySnapshotPublicationSkip

    data class CommittedTreeUnavailable(
        val failure: CommittedGitTreeFailure,
    ) : RepositorySnapshotPublicationSkip

    data class CommittedTreeMoved(
        val before: CommittedGitTree,
        val after: CommittedGitTreeResolution,
    ) : RepositorySnapshotPublicationSkip
}

sealed interface RepositoryBranch {
    data object Main : RepositoryBranch

    data object Unavailable : RepositoryBranch

    data class Other(val name: NonBlankString) : RepositoryBranch
}

sealed interface RepositorySnapshotPreparation {
    val overlaySeed: WorktreeOverlaySeed

    /**
     * Proof transition:
     * `RepositorySnapshotPreparation -> RepositorySnapshotPublication`.
     *
     * Captures the repository publication capability for one workspace
     * reconciliation. Managed full-index preparation refines the current Git
     * state into an exact clean-tree capability or a capability retaining the
     * finite [CommittedGitTreeFailure]. Raw Git extraction remains confined to
     * [CommittedGitTreeResolver].
     */
    fun capturePublication(): RepositorySnapshotPublication

    data object Unmanaged : RepositorySnapshotPreparation {
        override val overlaySeed = WorktreeOverlaySeed.None(WorktreeOverlayAbsence.UnmanagedWorkspace)

        override fun capturePublication(): RepositorySnapshotPublication =
            RepositorySnapshotPublication.Unmanaged
    }

    class Managed internal constructor(
        override val overlaySeed: WorktreeOverlaySeed,
        private val publicationAuthority: RepositorySnapshotPublicationAuthority,
    ) : RepositorySnapshotPreparation {
        override fun capturePublication(): RepositorySnapshotPublication = publicationAuthority.capture()
    }
}

private sealed interface GitBlobResolution {
    data class Resolved(val content: ByteArray) : GitBlobResolution

    data object Unavailable : GitBlobResolution
}

object RepositorySnapshotCoordinator {
    /**
     * Proof transition:
     * `(NormalizedPath, NormalizedPath, NormalizedPath, BuildClasspathFingerprint, ProducerVersion)`
     * `-> RepositorySnapshotPreparationResolution`.
     *
     * Derives both the exact overlay seed operation and the later repository
     * snapshot publication capability. An overlay-derived workspace receives a
     * publisher that cannot publish a full repository snapshot. Optional
     * optimization misses are retained as closed [WorktreeOverlayAbsence]
     * states rather than booleans or nulls. Invalid retained authorities,
     * content proofs, and shard publications are finite
     * [RepositorySnapshotPreparationFailure] rejection. The returned
     * preparation derives a publication capability for each reconciliation;
     * that capability retains its exact [CommittedGitTree], so completion
     * cannot relabel the reconciled database with a later tree. Raw paths are
     * extracted only at Git and filesystem boundaries.
     */
    fun prepare(
        workspaceRoot: NormalizedPath,
        repositoryDirectory: NormalizedPath,
        workspaceDatabase: NormalizedPath,
        buildClasspathFingerprint: BuildClasspathFingerprint,
        producerVersion: ProducerVersion,
    ): RepositorySnapshotPreparationResolution {
        val context = RepositorySnapshotContext(
            workspaceRoot,
            repositoryDirectory,
            buildClasspathFingerprint,
            producerVersion,
        )
        val databasePath = workspaceDatabase.toJavaPath()
        val overlayPath = databasePath.resolveSibling(OVERLAY_FILE)
        if (Files.exists(databasePath, LinkOption.NOFOLLOW_LINKS)) {
            val publicationAuthority = if (Files.exists(overlayPath, LinkOption.NOFOLLOW_LINKS)) {
                RepositorySnapshotPublicationAuthority.Suppressed
            } else {
                RepositorySnapshotPublicationAuthority.Eligible(context)
            }
            return RepositorySnapshotPreparationResolution.Resolved(
                RepositorySnapshotPreparation.Managed(
                    WorktreeOverlaySeed.None(WorktreeOverlayAbsence.ExistingWorkspaceDatabase),
                    publicationAuthority,
                ),
            )
        }
        val committedTree = when (val resolution = CommittedGitTreeResolver.resolve(workspaceRoot)) {
            is CommittedGitTreeResolution.Resolved -> resolution.tree
            is CommittedGitTreeResolution.Unavailable -> return fullIndex(
                context,
                WorktreeOverlayAbsence.CommittedTreeUnavailable(resolution.failure),
            )
        }
        val target = SnapshotManifest(
            key = SnapshotKey(
                committedTree.treeOid,
                buildClasspathFingerprint,
                SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
                producerVersion,
            ),
            files = committedTree.files,
            createdAt = SnapshotCreationEpochMillis.fromClock(System.currentTimeMillis()),
        )
        val snapshots = RepositorySnapshotStore(repositoryDirectory.toJavaPath())
        val retained = when (val resolution = snapshots.retainedManifests()) {
            is RepositorySnapshotInventoryResolution.Resolved -> resolution.manifests
            is RepositorySnapshotInventoryResolution.Rejected -> return RepositorySnapshotPreparationResolution.Rejected(
                RepositorySnapshotPreparationFailure.SnapshotMetadataRejected(resolution.failure),
            )
        }
        val base = when (val selection = RepositorySnapshotSelector.choose(target, retained)) {
            RepositorySnapshotSelection.NoCompatibleSnapshot -> return fullIndex(
                context,
                WorktreeOverlayAbsence.NoCompatibleRepositorySnapshot,
            )
            is RepositorySnapshotSelection.Selected -> selection.manifest
        }
        val baseDatabase = when (val resolution = snapshots.resolveSnapshotDatabase(base.key)) {
            is RepositorySnapshotDatabaseResolution.Resolved -> resolution.database.path
            is RepositorySnapshotDatabaseResolution.Rejected -> return RepositorySnapshotPreparationResolution.Rejected(
                RepositorySnapshotPreparationFailure.SnapshotDatabaseRejected(resolution.failure),
            )
        }
        val overlay = OverlayManifest.between(base, target, baseDatabase)
        val shardPayloads = mutableListOf<RepositoryContentShardPayload>()
        overlay.shards.values.toSet().forEach { shard ->
            when (val blob = gitBlob(workspaceRoot, shard.blobOid)) {
                is GitBlobResolution.Resolved -> when (
                    val proof = RepositoryContentShardPayload.prove(shard, blob.content)
                ) {
                    is RepositoryContentShardPayloadResolution.Proven -> shardPayloads += proof.payload
                    is RepositoryContentShardPayloadResolution.Rejected -> return RepositorySnapshotPreparationResolution.Rejected(
                        RepositorySnapshotPreparationFailure.BlobPayloadRejected(shard.blobOid, proof.failure),
                    )
                }
                GitBlobResolution.Unavailable -> return fullIndex(
                    context,
                    WorktreeOverlayAbsence.BlobUnavailable(shard.blobOid),
                )
            }
        }
        shardPayloads.forEach { payload ->
            when (val publication = snapshots.putContentShard(payload)) {
                is RepositoryContentShardPublicationResult.Published,
                is RepositoryContentShardPublicationResult.Reused,
                -> Unit
                is RepositoryContentShardPublicationResult.Rejected -> return RepositorySnapshotPreparationResolution.Rejected(
                    RepositorySnapshotPreparationFailure.ShardPublicationRejected(
                        payload.key.blobOid,
                        publication.failure,
                    ),
                )
            }
        }
        Files.createDirectories(databasePath.parent)
        val staged = Files.createTempFile(databasePath.parent, ".repository-overlay-", ".preparing")
        try {
            Files.writeString(staged, JSON.encodeToString(overlay))
            Files.move(staged, overlayPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staged)
        }
        return RepositorySnapshotPreparationResolution.Resolved(
            RepositorySnapshotPreparation.Managed(
                WorktreeOverlaySeed.Prepared(overlay),
                RepositorySnapshotPublicationAuthority.Suppressed,
            ),
        )
    }

    /**
     * Proof transition:
     * `(RepositorySnapshotContext, WorktreeOverlayAbsence)`
     * `-> RepositorySnapshotPreparationResolution.Resolved`.
     *
     * Derives a full-index preparation whose authority captures a fresh,
     * reconciliation-scoped [RepositorySnapshotPublication]. No raw path or
     * Git value is extracted here.
     */
    private fun fullIndex(
        context: RepositorySnapshotContext,
        reason: WorktreeOverlayAbsence,
    ): RepositorySnapshotPreparationResolution = RepositorySnapshotPreparationResolution.Resolved(
        RepositorySnapshotPreparation.Managed(
            WorktreeOverlaySeed.None(reason),
            RepositorySnapshotPublicationAuthority.Eligible(context),
        ),
    )

    internal fun currentBranch(workspaceRoot: NormalizedPath): RepositoryBranch = runCatching {
        val process = ReadOnlyGitCommand.currentBranch().processBuilder()
            .directory(workspaceRoot.toJavaPath().toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() != 0 || output.isBlank()) {
            RepositoryBranch.Unavailable
        } else if (output == "main") {
            RepositoryBranch.Main
        } else {
            RepositoryBranch.Other(NonBlankString(output))
        }
    }.getOrDefault(RepositoryBranch.Unavailable)

    private fun gitBlob(workspaceRoot: NormalizedPath, oid: GitObjectId): GitBlobResolution = runCatching {
        val process = ReadOnlyGitCommand.blob(oid.value).processBuilder()
            .directory(workspaceRoot.toJavaPath().toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val content = process.inputStream.use { it.readAllBytes() }
        if (process.waitFor() == 0) GitBlobResolution.Resolved(content) else GitBlobResolution.Unavailable
    }.getOrDefault(GitBlobResolution.Unavailable)

    private const val OVERLAY_FILE = "repository-overlay.json"
    private val JSON = Json { prettyPrint = true }
}
