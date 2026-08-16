package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.OpenCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.OpenWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedCanonicalWorkspacePublication
import io.github.amichne.kast.evidence.contract.PreparedWorkspacePublication
import io.github.amichne.kast.evidence.contract.WorkspaceGraphPublication
import io.github.amichne.kast.evidence.contract.WorkspacePublicationDiscard
import io.github.amichne.kast.evidence.contract.WorkspacePublicationFailure
import io.github.amichne.kast.evidence.contract.WorkspacePublicationOpening
import io.github.amichne.kast.evidence.contract.WorkspacePublicationPreparation
import io.github.amichne.kast.evidence.contract.WorkspacePublicationResult
import io.github.amichne.kast.evidence.contract.WorkspacePublicationTransaction
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import java.util.concurrent.CancellationException

/** Canonical publication transaction backed directly by one SQLite publication database. */
class SqliteCanonicalWorkspacePublicationTransaction private constructor(
    private val delegate: SqliteWorkspaceGenerationPublication,
) : WorkspacePublicationTransaction {
    constructor(database: SqliteWorkspacePublicationDatabase) : this(
        SqliteWorkspaceGenerationPublication(database),
    )

    internal constructor(
        database: SqliteWorkspacePublicationDatabase,
        faultInjector: SqliteWorkspacePublicationFaultInjector,
    ) : this(SqliteWorkspaceGenerationPublication.faultInjecting(database, faultInjector))

    private val owner = Owner()

    override fun begin(): WorkspacePublicationOpening = try {
        WorkspacePublicationOpening.Opened(OwnedOpen(delegate.begin(), owner))
    } catch (failure: Exception) {
        failure.rejectedOpening()
    }

    override fun prepare(
        open: OpenCanonicalWorkspacePublication,
        candidate: ReconciledWorkspace,
    ): WorkspacePublicationPreparation = when (val admission = admit(open)) {
        OpenAdmission.Rejected -> WorkspacePublicationPreparation.Rejected(
            WorkspacePublicationFailure.CapabilityUnavailable,
        )
        is OpenAdmission.Owned -> try {
            WorkspacePublicationPreparation.Prepared(
                OwnedPrepared(
                    publication = delegate.prepare(
                        admission.publication,
                        candidate.candidate.sourceState,
                        WorkspaceGraphPublication.Ready,
                    ),
                    candidate = candidate,
                    owner = owner,
                ),
            )
        } catch (failure: Exception) {
            failure.rejectedPreparation()
        }
    }

    override fun commit(
        prepared: PreparedCanonicalWorkspacePublication,
    ): WorkspacePublicationResult = when (val admission = admit(prepared)) {
        PreparedAdmission.Rejected -> WorkspacePublicationResult.Rejected(
            WorkspacePublicationFailure.CapabilityUnavailable,
        )
        is PreparedAdmission.Owned -> try {
            val committed = delegate.commit(admission.publication)
            WorkspacePublicationResult.Published(
                PublishedWorkspace.publish(
                    admission.candidate,
                    committed.commit.publication.generation,
                ),
            )
        } catch (failure: Exception) {
            failure.rejectedResult()
        }
    }

    override fun discard(
        open: OpenCanonicalWorkspacePublication,
    ): WorkspacePublicationDiscard = when (val admission = admit(open)) {
        OpenAdmission.Rejected -> WorkspacePublicationDiscard.Rejected(
            WorkspacePublicationFailure.CapabilityUnavailable,
        )
        is OpenAdmission.Owned -> try {
            delegate.discard(admission.publication)
            WorkspacePublicationDiscard.Discarded
        } catch (failure: Exception) {
            failure.rejectedDiscard()
        }
    }

    override fun discard(
        prepared: PreparedCanonicalWorkspacePublication,
    ): WorkspacePublicationDiscard = when (val admission = admit(prepared)) {
        PreparedAdmission.Rejected -> WorkspacePublicationDiscard.Rejected(
            WorkspacePublicationFailure.CapabilityUnavailable,
        )
        is PreparedAdmission.Owned -> try {
            delegate.discard(admission.publication)
            WorkspacePublicationDiscard.Discarded
        } catch (failure: Exception) {
            failure.rejectedDiscard()
        }
    }

    /**
     * Proof transition: `OpenCanonicalWorkspacePublication -> OpenAdmission`.
     *
     * Establishes that the open capability belongs to this exact transaction. The finite rejected
     * state exposes no underlying SQLite capability.
     */
    private fun admit(open: OpenCanonicalWorkspacePublication): OpenAdmission {
        val candidate = open as? OwnedOpen ?: return OpenAdmission.Rejected
        return if (candidate.owner === owner) {
            OpenAdmission.Owned(candidate.publication)
        } else {
            OpenAdmission.Rejected
        }
    }

    /**
     * Proof transition: `PreparedCanonicalWorkspacePublication -> PreparedAdmission`.
     *
     * Establishes that the prepared capability and reconciled candidate belong to this exact
     * transaction. The finite rejected state exposes neither value.
     */
    private fun admit(prepared: PreparedCanonicalWorkspacePublication): PreparedAdmission {
        val candidate = prepared as? OwnedPrepared ?: return PreparedAdmission.Rejected
        return if (candidate.owner === owner) {
            PreparedAdmission.Owned(candidate.publication, candidate.candidate)
        } else {
            PreparedAdmission.Rejected
        }
    }

    private fun Exception.rejectedOpening(): WorkspacePublicationOpening.Rejected {
        rethrowCancellation()
        return WorkspacePublicationOpening.Rejected(WorkspacePublicationFailure.StorageUnavailable)
    }

    private fun Exception.rejectedPreparation(): WorkspacePublicationPreparation.Rejected {
        rethrowCancellation()
        return WorkspacePublicationPreparation.Rejected(WorkspacePublicationFailure.StorageUnavailable)
    }

    private fun Exception.rejectedResult(): WorkspacePublicationResult.Rejected {
        rethrowCancellation()
        return WorkspacePublicationResult.Rejected(WorkspacePublicationFailure.StorageUnavailable)
    }

    private fun Exception.rejectedDiscard(): WorkspacePublicationDiscard.Rejected {
        rethrowCancellation()
        return WorkspacePublicationDiscard.Rejected(WorkspacePublicationFailure.StorageUnavailable)
    }

    private fun Exception.rethrowCancellation() {
        if (this is CancellationException) throw this
    }

    private class Owner

    private data class OwnedOpen(
        val publication: OpenWorkspacePublication,
        val owner: Owner,
    ) : OpenCanonicalWorkspacePublication

    private data class OwnedPrepared(
        val publication: PreparedWorkspacePublication,
        val candidate: ReconciledWorkspace,
        val owner: Owner,
    ) : PreparedCanonicalWorkspacePublication

    private sealed interface OpenAdmission {
        data class Owned(
            val publication: OpenWorkspacePublication,
        ) : OpenAdmission

        data object Rejected : OpenAdmission
    }

    private sealed interface PreparedAdmission {
        data class Owned(
            val publication: PreparedWorkspacePublication,
            val candidate: ReconciledWorkspace,
        ) : PreparedAdmission

        data object Rejected : PreparedAdmission
    }
}
