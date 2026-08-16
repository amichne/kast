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
import io.github.amichne.kast.indexstore.snapshot.WorkspaceGenerationStore
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.ReconciledWorkspace
import java.util.concurrent.CancellationException

/**
 * Canonical publication transaction over the existing atomic source-index generation store.
 *
 * This adapter adds no schema or parallel authority. Reconciliation runs while its open store
 * transaction is active; prepare and commit retain that same owner-specific capability.
 */
class IndexStoreCanonicalWorkspacePublicationTransaction(
    store: WorkspaceGenerationStore,
) : WorkspacePublicationTransaction {
    private val delegate = IndexStoreWorkspaceGenerationPublication(store)
    private val owner = Owner()

    override fun begin(): WorkspacePublicationOpening = try {
        WorkspacePublicationOpening.Opened(OwnedOpen(delegate.begin(), owner))
    } catch (failure: Exception) {
        rejectedOpening(failure)
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
                    delegate = delegate.prepare(
                        open = admission.publication,
                        identity = candidate.candidate.sourceState,
                        graphPublication = WorkspaceGraphPublication.Ready,
                    ),
                    candidate = candidate,
                    owner = owner,
                ),
            )
        } catch (failure: Exception) {
            rejectedPreparation(failure)
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
                    reconciled = admission.candidate,
                    generation = committed.commit.publication.generation,
                ),
            )
        } catch (failure: Exception) {
            rejectedResult(failure)
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
            rejectedDiscard(failure)
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
            rejectedDiscard(failure)
        }
    }

    /**
     * Proof transition: `OpenCanonicalWorkspacePublication -> OpenAdmission`.
     *
     * Establishes that an open capability belongs to this exact adapter instance. The closed
     * rejection is retained without exposing the store capability outside this adapter.
     */
    private fun admit(open: OpenCanonicalWorkspacePublication): OpenAdmission {
        val candidate = open as? OwnedOpen ?: return OpenAdmission.Rejected
        return if (candidate.owner === owner) {
            OpenAdmission.Owned(candidate.delegate)
        } else {
            OpenAdmission.Rejected
        }
    }

    /**
     * Proof transition: `PreparedCanonicalWorkspacePublication -> PreparedAdmission`.
     *
     * Establishes that a prepared capability and reconciled candidate belong to this exact
     * adapter instance. The closed rejection exposes neither value.
     */
    private fun admit(prepared: PreparedCanonicalWorkspacePublication): PreparedAdmission {
        val candidate = prepared as? OwnedPrepared ?: return PreparedAdmission.Rejected
        return if (candidate.owner === owner) {
            PreparedAdmission.Owned(candidate.delegate, candidate.candidate)
        } else {
            PreparedAdmission.Rejected
        }
    }

    private fun rejectedOpening(failure: Exception): WorkspacePublicationOpening.Rejected {
        rethrowCancellation(failure)
        return WorkspacePublicationOpening.Rejected(WorkspacePublicationFailure.StorageUnavailable)
    }

    private fun rejectedPreparation(failure: Exception): WorkspacePublicationPreparation.Rejected {
        rethrowCancellation(failure)
        return WorkspacePublicationPreparation.Rejected(WorkspacePublicationFailure.StorageUnavailable)
    }

    private fun rejectedResult(failure: Exception): WorkspacePublicationResult.Rejected {
        rethrowCancellation(failure)
        return WorkspacePublicationResult.Rejected(WorkspacePublicationFailure.StorageUnavailable)
    }

    private fun rejectedDiscard(failure: Exception): WorkspacePublicationDiscard.Rejected {
        rethrowCancellation(failure)
        return WorkspacePublicationDiscard.Rejected(WorkspacePublicationFailure.StorageUnavailable)
    }

    private fun rethrowCancellation(failure: Exception) {
        if (failure is CancellationException) throw failure
    }

    private class Owner

    private data class OwnedOpen(
        val delegate: OpenWorkspacePublication,
        val owner: Owner,
    ) : OpenCanonicalWorkspacePublication

    private data class OwnedPrepared(
        val delegate: PreparedWorkspacePublication,
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
