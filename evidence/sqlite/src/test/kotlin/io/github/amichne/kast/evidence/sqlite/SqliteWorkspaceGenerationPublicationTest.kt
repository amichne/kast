package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationDiscard
import io.github.amichne.kast.evidence.contract.WorkspacePublicationFailure
import io.github.amichne.kast.evidence.contract.WorkspacePublicationOpening
import io.github.amichne.kast.evidence.contract.WorkspacePublicationPreparation
import io.github.amichne.kast.evidence.contract.WorkspacePublicationResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteWorkspaceGenerationPublicationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `generation authority commits one durable publication directly to SQLite`() {
        val database = publicationDatabase(tempDir.resolve("generation/workspace.db"))
        val authority = SqliteWorkspaceGenerationPublication(database)
        val identity = WorkspaceStateIdentity("verified-state")

        val prepared = authority.prepare(
            authority.begin(),
            identity,
            WorkspaceGraphPublication.Ready,
        )
        assertEquals(PublishedWorkspaceGenerationState.Unpublished, authority.current())

        val committed = authority.commit(prepared).commit.publication
        val reopened = SqliteWorkspaceGenerationPublication(
            publicationDatabase(tempDir.resolve("generation/workspace.db")),
        )

        assertEquals(1, committed.generation.value)
        assertEquals(identity, committed.identity)
        assertEquals(
            PublishedWorkspaceGenerationState.Published(committed),
            reopened.current(),
        )
    }

    @Test
    fun `discarded generation candidate cannot replace the prior publication`() {
        val database = publicationDatabase(tempDir.resolve("discard/workspace.db"))
        val authority = SqliteWorkspaceGenerationPublication(database)
        val first = publish(authority, WorkspaceStateIdentity("first"))
        val prepared = authority.prepare(
            authority.begin(),
            WorkspaceStateIdentity("discarded"),
            WorkspaceGraphPublication.Ready,
        )

        authority.discard(prepared)

        assertEquals(
            PublishedWorkspaceGenerationState.Published(first),
            authority.current(),
        )
    }

    @Test
    fun `canonical commit publishes complete state and preserves prior generation on failure`() {
        val database = publicationDatabase(tempDir.resolve("canonical/workspace.db"))
        val firstTransaction = SqliteCanonicalWorkspacePublicationTransaction(database)
        val firstCandidate = reconciled(tempDir.resolve("workspace"), "first")
        val first = publish(firstTransaction, firstCandidate)
        val prior = SqliteWorkspaceGenerationPublication(database).current()
        val failingTransaction = SqliteCanonicalWorkspacePublicationTransaction(
            database,
            faultInjector = SqliteWorkspacePublicationFaultInjector { point ->
                if (point == SqliteWorkspacePublicationFaultPoint.BEFORE_COMMIT) {
                    error("injected commit failure")
                }
            },
        )
        val open = (failingTransaction.begin() as WorkspacePublicationOpening.Opened).publication
        val prepared = (
            failingTransaction.prepare(
                open,
                reconciled(tempDir.resolve("workspace"), "next"),
            ) as WorkspacePublicationPreparation.Prepared
        ).publication

        assertEquals(
            WorkspacePublicationResult.Rejected(WorkspacePublicationFailure.StorageUnavailable),
            failingTransaction.commit(prepared),
        )
        assertEquals(WorkspacePublicationDiscard.Discarded, failingTransaction.discard(prepared))
        assertEquals(prior, SqliteWorkspaceGenerationPublication(database).current())
        assertEquals(1, first.generation.value)
        assertEquals(firstCandidate.candidate.root, first.root)
        assertEquals(firstCandidate.candidate.sourceState, first.sourceState)
        assertEquals(WorkspaceEvidenceKind.entries.toSet(), first.coverage.evidence)
    }

    private fun publicationDatabase(path: Path): SqliteWorkspacePublicationDatabase {
        Files.createDirectories(path.parent)
        return when (val opened = SqliteWorkspacePublicationDatabase.open(path)) {
            is SqliteWorkspacePublicationDatabaseOpening.Opened -> opened.database
            is SqliteWorkspacePublicationDatabaseOpening.Rejected -> error(opened.failure)
        }
    }

    private fun publish(
        authority: SqliteWorkspaceGenerationPublication,
        identity: WorkspaceStateIdentity,
    ): PublishedWorkspaceGeneration = authority.commit(
        authority.prepare(
            authority.begin(),
            identity,
            WorkspaceGraphPublication.Ready,
        ),
    ).commit.publication

    private fun publish(
        transaction: SqliteCanonicalWorkspacePublicationTransaction,
        candidate: ReconciledWorkspace,
    ) = (transaction.commit(
        (
            transaction.prepare(
                (transaction.begin() as WorkspacePublicationOpening.Opened).publication,
                candidate,
            ) as WorkspacePublicationPreparation.Prepared
        ).publication,
    ) as WorkspacePublicationResult.Published).workspace

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
}
