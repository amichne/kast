package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationFailure
import io.github.amichne.kast.evidence.contract.WorkspacePublicationDiscard
import io.github.amichne.kast.evidence.contract.WorkspacePublicationOpening
import io.github.amichne.kast.evidence.contract.WorkspacePublicationPreparation
import io.github.amichne.kast.evidence.contract.WorkspacePublicationResult
import io.github.amichne.kast.indexstore.snapshot.PublicationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceIdentity
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState as StoredPublicationState
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStore
import io.github.amichne.kast.indexstore.snapshot.WorkspaceSemanticGeneration
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteWorkspaceGenerationPublicationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `adapter commits the existing atomic workspace generation`() {
        val database = tempDir.resolve("data/cache/source-index.db")
        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            seedCompleteModule(database)
            val adapter = IndexStoreWorkspaceGenerationPublication(
                WorkspaceGenerationStore(
                    sourceStore,
                    publicationClock = { PublicationEpochMillis.fromClock(42) },
                ),
            )

            val open = adapter.begin()
            sourceStore.writeHeadCommit("staged-head")
            val identity = WorkspaceStateIdentity("verified-state")
            val prepared = adapter.prepare(
                open,
                identity,
                WorkspaceGraphPublication.Ready,
            )

            assertEquals(PublishedWorkspaceGenerationState.Unpublished, adapter.current())
            val published = adapter.commit(prepared)
            val expectedPublication = PublishedWorkspaceGeneration(
                generation = evidenceGeneration(1),
                identity = identity,
            )

            assertEquals(expectedPublication, published.commit.publication)
            assertEquals(
                PublishedWorkspaceGenerationState.Published(expectedPublication),
                adapter.current(),
            )
            val storedCommit = adapter.storedCommit(published.commit)
            assertEquals(WorkspaceSemanticGeneration(1), storedCommit.manifest.generation)
            assertEquals(PublishedWorkspaceIdentity("verified-state"), storedCommit.manifest.identity)
            assertEquals(
                IndexStoreWorkspacePublicationCurrency.Current(storedCommit.manifest),
                adapter.currency(storedCommit.manifest),
            )
            val differentManifest = storedCommit.manifest.copy(
                identity = PublishedWorkspaceIdentity("different-state"),
            )
            assertEquals(
                IndexStoreWorkspacePublicationCurrency.Moved(
                    expected = differentManifest,
                    observed = StoredPublicationState.Published(storedCommit.manifest),
                ),
                adapter.currency(differentManifest),
            )
            assertEquals("staged-head", readHeadCommit(database))
        }
    }

    @Test
    fun `adapter discard rolls back staged source-index facts`() {
        val database = tempDir.resolve("discard/cache/source-index.db")
        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            seedCompleteModule(database)
            val adapter = IndexStoreWorkspaceGenerationPublication(
                WorkspaceGenerationStore(sourceStore),
            )
            val open = adapter.begin()
            sourceStore.writeHeadCommit("discarded-head")

            adapter.discard(
                adapter.prepare(
                    open,
                    WorkspaceStateIdentity("discarded-state"),
                    WorkspaceGraphPublication.Ready,
                ),
            )

            assertEquals(PublishedWorkspaceGenerationState.Unpublished, adapter.current())
            assertEquals(null, readHeadCommit(database))
        }
    }

    @Test
    fun `canonical transaction publishes complete state and preserves prior generation on failure`() {
        val database = tempDir.resolve("canonical/cache/source-index.db")
        SqliteSourceIndexStore(workspaceIdentity(database)).use { sourceStore ->
            sourceStore.ensureSchema()
            seedCompleteModule(database)
            val store = WorkspaceGenerationStore(
                sourceStore,
                publicationClock = { PublicationEpochMillis.fromClock(42) },
            )
            val transaction = IndexStoreCanonicalWorkspacePublicationTransaction(store)
            val candidate = reconciled(database.parent.parent.resolve("workspace"), "first")

            val open = (transaction.begin() as WorkspacePublicationOpening.Opened).publication
            val prepared = (
                transaction.prepare(open, candidate) as WorkspacePublicationPreparation.Prepared
            ).publication
            val published = transaction.commit(prepared)
            val workspace = (published as WorkspacePublicationResult.Published).workspace
            val prior = store.current()

            assertEquals(1, workspace.generation.value)
            assertEquals(candidate.candidate.root, workspace.root)
            assertEquals(candidate.candidate.sourceState, workspace.sourceState)
            assertEquals(WorkspaceEvidenceKind.entries.toSet(), workspace.coverage.evidence)

            markModuleIncomplete(database)
            val rejectedOpen = (
                transaction.begin() as WorkspacePublicationOpening.Opened
            ).publication
            sourceStore.writeHeadCommit("discarded-candidate")
            assertEquals(
                WorkspacePublicationResult.Rejected(WorkspacePublicationFailure.StorageUnavailable),
                transaction.prepare(
                    rejectedOpen,
                    reconciled(database.parent.parent.resolve("workspace"), "next"),
                ).let { preparation ->
                    when (preparation) {
                        is WorkspacePublicationPreparation.Prepared ->
                            transaction.commit(preparation.publication)
                        is WorkspacePublicationPreparation.Rejected ->
                            WorkspacePublicationResult.Rejected(preparation.failure)
                    }
                },
            )
            assertEquals(WorkspacePublicationDiscard.Discarded, transaction.discard(rejectedOpen))
            assertEquals(prior, store.current())
            assertEquals(null, readHeadCommit(database))
        }
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

    private fun seedCompleteModule(database: Path) {
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """INSERT INTO module_index_progress(
                           module_name, relationship_index_status, indexed_file_count,
                           total_file_count, last_indexed_epoch_ms
                       ) VALUES ('app', 'COMPLETE', 1, 1, 1)""",
                )
            }
        }
    }

    private fun markModuleIncomplete(database: Path) {
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE module_index_progress SET relationship_index_status = 'PENDING'",
                )
            }
        }
    }

    private fun reconciled(root: Path, identity: String): ReconciledWorkspace {
        val canonicalRoot = when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(root)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error(admitted.failure)
        }
        return when (
            val admitted = ReconciledWorkspace.admit(
                WorkspaceCandidate(canonicalRoot, WorkspaceStateIdentity(identity)),
                WorkspaceEvidenceKind.entries.toSet(),
            )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error(admitted.failure.missing)
        }
    }

    private fun readHeadCommit(database: Path): String? =
        DriverManager.getConnection("jdbc:sqlite:${database.toUri()}?mode=ro").use { connection ->
            connection.prepareStatement("SELECT head_commit FROM schema_version LIMIT 1").use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
    }
}

private fun evidenceGeneration(value: Long): EvidenceGeneration = when (val parsed = EvidenceGeneration.parse(value)) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error(parsed.failure)
}
