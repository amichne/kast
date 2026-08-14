package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.snapshot.evidence.*
import io.github.amichne.kast.indexstore.store.DurableEvidenceCandidateCheckpointStore
import io.github.amichne.kast.indexstore.store.DurableEvidenceLanePublicationStore
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DurableEvidenceLaneStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `candidate checkpoint is durable resumable and invisible until atomic publication`() {
        val database = tempDir.resolve("durable/cache/source-index.db")
        val identity = candidateIdentity(DurableEvidenceLane.SOURCE, "workspace-a", 'a')
        val batch = candidateBatch("src/main/kotlin/App.kt", 'b', "source-v1", "source-payload")

        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            assertEquals(17, SOURCE_INDEX_SCHEMA_VERSION)

            assertInstanceOf(
                CandidateCheckpointResolution.Checkpointed::class.java,
                sourceStore.durableEvidenceCandidates().checkpoint(identity, batch),
            )
            assertEquals(
                EvidenceLanePublicationState.Unpublished,
                sourceStore.durableEvidencePublications().published(DurableEvidenceLane.SOURCE),
            )
        }

        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            assertEquals(
                CandidateResumeResolution.Resumable(identity, batch.shards),
                sourceStore.durableEvidenceCandidates().resume(identity),
            )
            assertInstanceOf(
                CandidateResumeResolution.Rejected::class.java,
                sourceStore.durableEvidenceCandidates().resume(
                    candidateIdentity(DurableEvidenceLane.SOURCE, "workspace-b", 'a'),
                ),
            )

            val publication = sourceStore.durableEvidencePublications().publish(
                identity = identity,
                expectation = EvidenceLanePublicationExpectation.Unpublished,
                publishedAt = PublicationEpochMillis.fromClock(42),
            )
            val published = assertInstanceOf(EvidenceLanePublicationResolution.Published::class.java, publication)

            assertEquals(EvidenceRevision.first(), published.publication.current.revision)
            assertEquals(identity, published.publication.current.identity)
            assertEquals(batch.shards, published.publication.current.shards)
            assertEquals(PreviousEvidenceLanePublication.Absent, published.publication.previous)
            assertEquals(
                published.publication,
                sourceStore.durableEvidencePublications().published(DurableEvidenceLane.SOURCE),
            )
            assertEquals(CandidateResumeResolution.Absent, sourceStore.durableEvidenceCandidates().resume(identity))
        }
    }

    @Test
    fun `failed lane CAS cannot revoke independently published lanes`() {
        val database = tempDir.resolve("independent/cache/source-index.db")
        val sourceIdentity = candidateIdentity(DurableEvidenceLane.SOURCE, "workspace-a", 'a')
        val referenceIdentity = candidateIdentity(DurableEvidenceLane.REFERENCES, "workspace-a", 'a')
        val graphIdentity = candidateIdentity(DurableEvidenceLane.SEMANTIC_GRAPH, "workspace-b", 'c')

        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            val candidates = sourceStore.durableEvidenceCandidates()
            val publications = sourceStore.durableEvidencePublications()
            val source = checkpointAndPublish(
                candidates,
                publications,
                sourceIdentity,
                candidateBatch("src/A.kt", '1', "source-v1", "source"),
            )
            val references = checkpointAndPublish(
                candidates,
                publications,
                referenceIdentity,
                candidateBatch("src/A.kt", '1', "references-v1", "references"),
            )
            candidates.checkpoint(
                graphIdentity,
                candidateBatch("src/A.kt", '1', "graph-v1", "graph"),
            )

            val failed = publications.publish(
                identity = graphIdentity,
                expectation = EvidenceLanePublicationExpectation.Published(
                    source.current.identity,
                    source.current.revision,
                ),
                publishedAt = PublicationEpochMillis.fromClock(99),
            )

            assertInstanceOf(EvidenceLanePublicationResolution.Rejected::class.java, failed)
            assertEquals(source, publications.published(DurableEvidenceLane.SOURCE))
            assertEquals(references, publications.published(DurableEvidenceLane.REFERENCES))
            assertEquals(
                EvidenceLanePublicationState.Unpublished,
                publications.published(DurableEvidenceLane.SEMANTIC_GRAPH),
            )
            assertEquals(
                CandidateResumeResolution.Resumable(
                    graphIdentity,
                    candidateBatch("src/A.kt", '1', "graph-v1", "graph").shards,
                ),
                candidates.resume(graphIdentity),
            )
        }
    }

    @Test
    fun `lane publication retains the exact prior set with explicit previous state`() {
        val database = tempDir.resolve("retained/cache/source-index.db")
        val firstIdentity = candidateIdentity(DurableEvidenceLane.SOURCE, "workspace-a", 'a')
        val secondIdentity = candidateIdentity(DurableEvidenceLane.SOURCE, "workspace-b", 'b')

        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            val candidates = sourceStore.durableEvidenceCandidates()
            val publications = sourceStore.durableEvidencePublications()
            val first = checkpointAndPublish(
                candidates,
                publications,
                firstIdentity,
                candidateBatch("src/A.kt", '1', "source-v1", "first"),
            )
            candidates.checkpoint(
                secondIdentity,
                candidateBatch("src/A.kt", '2', "source-v1", "second"),
            )

            val result = publications.publish(
                secondIdentity,
                EvidenceLanePublicationExpectation.Published(first.current.identity, first.current.revision),
                PublicationEpochMillis.fromClock(84),
            )
            val second = assertInstanceOf(
                EvidenceLanePublicationResolution.Published::class.java,
                result,
            ).publication

            assertEquals(secondIdentity, second.current.identity)
            assertEquals(first.current.revision.next(), second.current.revision)
            assertEquals(PreviousEvidenceLanePublication.Retained(first.current), second.previous)
            assertEquals(second, publications.published(DurableEvidenceLane.SOURCE))
        }
    }

    @Test
    fun `checkpoint cannot be absorbed by a legacy workspace transaction`() {
        val database = tempDir.resolve("legacy-write/cache/source-index.db")
        val identity = candidateIdentity(DurableEvidenceLane.SOURCE, "workspace-a", 'a')
        val batch = candidateBatch("src/A.kt", '1', "source-v1", "candidate")

        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            val legacyWrite = sourceStore.beginWorkspaceWrite()

            assertEquals(
                CandidateCheckpointResolution.Rejected(CandidateCheckpointFailure.WorkspaceWriteActive),
                sourceStore.durableEvidenceCandidates().checkpoint(identity, batch),
            )

            sourceStore.discardWorkspaceWrite(legacyWrite)
            assertEquals(CandidateResumeResolution.Absent, sourceStore.durableEvidenceCandidates().resume(identity))
        }
    }

    private fun checkpointAndPublish(
        candidates: DurableEvidenceCandidateCheckpointStore,
        publications: DurableEvidenceLanePublicationStore,
        identity: DurableEvidenceCandidateIdentity,
        batch: DurableEvidenceCandidateBatch,
    ): EvidenceLanePublicationState.Published {
        assertInstanceOf(CandidateCheckpointResolution.Checkpointed::class.java, candidates.checkpoint(identity, batch))
        val result = publications.publish(
            identity,
            EvidenceLanePublicationExpectation.Unpublished,
            PublicationEpochMillis.fromClock(42),
        )
        return assertInstanceOf(EvidenceLanePublicationResolution.Published::class.java, result).publication
    }

    private fun candidateIdentity(
        lane: DurableEvidenceLane,
        workspace: String,
        environmentDigit: Char,
    ): DurableEvidenceCandidateIdentity = DurableEvidenceCandidateIdentity(
        lane = lane,
        workspace = PublishedWorkspaceIdentity(workspace),
        environment = environmentFingerprint(environmentDigit),
    )

    private fun candidateBatch(
        path: String,
        hashDigit: Char,
        stageVersion: String,
        payload: String,
    ): DurableEvidenceCandidateBatch = when (
        val resolution = DurableEvidenceCandidateBatch.refine(
            listOf(
                DurableEvidenceCandidateShard(
                    path = candidatePath(path),
                    contentHash = CandidateContentHash.refine(hashDigit.toString().repeat(64)).resolved(),
                    stageVersion = CandidateStageVersion.refine(stageVersion).resolved(),
                    payload = CandidateShardPayload.refine(payload).resolved(),
                ),
            ),
        )
    ) {
        is DurableEvidenceCandidateBatchResolution.Resolved -> resolution.batch
        is DurableEvidenceCandidateBatchResolution.Rejected -> error(resolution.failure)
    }

    private fun candidatePath(path: String): CandidateShardPath = CandidateShardPath.refine(path).resolved()

    private fun environmentFingerprint(digit: Char): CandidateEnvironmentFingerprint =
        CandidateEnvironmentFingerprint.refine(digit.toString().repeat(64)).resolved()

    private fun workspaceIdentity(database: Path): WorkspaceIdentity {
        val root = database.parent.parent.resolve("workspace")
        Files.createDirectories(root)
        return WorkspaceIdentity.fromWorkspaceRoot(root).copy(
            workspaceDataDirectory = NormalizedPath.ofAbsolute(database.parent.parent),
            workspaceCacheDirectory = NormalizedPath.ofAbsolute(database.parent),
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(database),
        )
    }

    private fun <Value, Failure> EvidenceValueRefinement<Value, Failure>.resolved(): Value = when (this) {
        is EvidenceValueRefinement.Refined -> value
        is EvidenceValueRefinement.Rejected -> error(failure.toString())
    }
}
