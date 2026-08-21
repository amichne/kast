package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.GenerationPublication
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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

        val committed = (
            authority.commit(prepared) as GenerationPublication.Published
        ).commit.publication
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
    fun `identical canonical state preserves the durable generation`() {
        val database = publicationDatabase(tempDir.resolve("unchanged/workspace.db"))
        val authority = SqliteWorkspaceGenerationPublication(database)
        val identity = WorkspaceStateIdentity("stable-state")
        val first = publish(authority, identity)

        repeat(100) {
            val unchanged = authority.commit(
                authority.prepare(
                    authority.begin(),
                    identity,
                    WorkspaceGraphPublication.Ready,
                ),
            )
            assertInstanceOf(GenerationPublication.Unchanged::class.java, unchanged)
        }
        val retained = (
            authority.current() as PublishedWorkspaceGenerationState.Published
        ).publication
        assertEquals(first, retained)
        assertEquals(1L, retained.generation.value)
    }

    @Test
    fun `canonical identical publication returns unchanged with the same read lease`() {
        val database = publicationDatabase(tempDir.resolve("canonical-unchanged/workspace.db"))
        val transaction = SqliteCanonicalWorkspacePublicationTransaction(database)
        val candidate = reconciled(tempDir.resolve("workspace"), "stable-state")
        val first = publish(transaction, candidate)
        val prepared = (
            transaction.prepare(
                (transaction.begin() as WorkspacePublicationOpening.Opened).publication,
                candidate,
            ) as WorkspacePublicationPreparation.Prepared
        ).publication

        val unchanged = assertInstanceOf(
            WorkspacePublicationResult.Unchanged::class.java,
            transaction.commit(prepared),
        )

        assertEquals(first.readLease, unchanged.workspace.readLease)
        assertEquals(first.sourceState, unchanged.workspace.sourceState)
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
    ): PublishedWorkspaceGeneration = (
        authority.commit(
            authority.prepare(
                authority.begin(),
                identity,
                WorkspaceGraphPublication.Ready,
            ),
        ) as GenerationPublication.Published
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
    ) as WorkspacePublicationResult.Advanced).workspace

    private fun reconciled(
        root: Path,
        identity: String,
    ): ReconciledWorkspace {
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
