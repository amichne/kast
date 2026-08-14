package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.evidence.contract.*
import io.github.amichne.kast.evidence.spi.EvidenceCandidateCheckpointAuthority
import io.github.amichne.kast.evidence.spi.PersistedEvidenceLanePublicationAuthority
import io.github.amichne.kast.indexstore.snapshot.PublicationEpochMillis
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteIncrementalEvidenceAuthorityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `host-neutral authority resumes invisible candidate and publishes one lane`() {
        val database = tempDir.resolve("incremental/cache/source-index.db")
        val identity = candidateIdentity(PersistedEvidenceLane.Source, "workspace-a", 'a')
        val batch = candidateBatch("src/main/kotlin/App.kt", 'b', "source-v1", "payload")

        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            val adapter = IndexStoreIncrementalEvidenceAuthority(
                sourceStore,
                publicationClock = { PublicationEpochMillis.fromClock(42) },
            )
            val candidates: EvidenceCandidateCheckpointAuthority = adapter.candidates
            val publications: PersistedEvidenceLanePublicationAuthority = adapter.publications

            assertInstanceOf(
                EvidenceCandidateCheckpointResolution.Checkpointed::class.java,
                candidates.checkpoint(identity, batch),
            )
            assertEquals(
                PersistedEvidenceLanePublicationState.Unpublished,
                publications.published(PersistedEvidenceLane.Source),
            )
        }

        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            val adapter = IndexStoreIncrementalEvidenceAuthority(
                sourceStore,
                publicationClock = { PublicationEpochMillis.fromClock(42) },
            )
            val candidates: EvidenceCandidateCheckpointAuthority = adapter.candidates
            val publications: PersistedEvidenceLanePublicationAuthority = adapter.publications

            assertEquals(
                EvidenceCandidateResumeResolution.Resumable(identity, batch.shards),
                candidates.resume(identity),
            )
            val result = publications.publish(
                identity,
                PersistedEvidenceLanePublicationExpectation.Unpublished,
            )
            val published = assertInstanceOf(
                PersistedEvidenceLanePublicationResolution.Published::class.java,
                result,
            ).publication

            assertEquals(EvidenceLaneRevision.first(), published.current.revision)
            assertEquals(identity, published.current.identity)
            assertEquals(batch.shards, published.current.shards)
            assertEquals(PreviousPersistedEvidencePublication.Absent, published.previous)
            assertEquals(published, publications.published(PersistedEvidenceLane.Source))
        }
    }

    private fun candidateIdentity(
        lane: PersistedEvidenceLane,
        workspace: String,
        environmentDigit: Char,
    ): EvidenceCandidateIdentity = EvidenceCandidateIdentity(
        lane,
        WorkspaceStateIdentity(workspace),
        EvidenceCandidateEnvironment.refine(environmentDigit.toString().repeat(64)).refined(),
    )

    private fun candidateBatch(
        path: String,
        hashDigit: Char,
        stageVersion: String,
        payload: String,
    ): EvidenceCandidateBatch = when (
        val resolution = EvidenceCandidateBatch.refine(
            listOf(
                EvidenceCandidateShard(
                    WorkspaceSourcePath.parse(path).refined(),
                    WorkspaceSourceContentHash.parse(hashDigit.toString().repeat(64)).refined(),
                    EvidenceCandidateStageVersion.refine(stageVersion).refined(),
                    EvidenceCandidatePayload.refine(payload).refined(),
                ),
            ),
        )
    ) {
        is EvidenceCandidateBatchResolution.Refined -> resolution.batch
        is EvidenceCandidateBatchResolution.Rejected -> error(resolution.failure.toString())
    }

    private fun workspaceIdentity(database: Path): WorkspaceIdentity {
        val root = database.parent.parent.resolve("workspace")
        Files.createDirectories(root)
        return WorkspaceIdentity.fromWorkspaceRoot(root).copy(
            workspaceDataDirectory = NormalizedPath.ofAbsolute(database.parent.parent),
            workspaceCacheDirectory = NormalizedPath.ofAbsolute(database.parent),
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(database),
        )
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
