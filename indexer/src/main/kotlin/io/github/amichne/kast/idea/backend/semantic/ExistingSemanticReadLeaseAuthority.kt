package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.spi.OpenSemanticReadLease
import io.github.amichne.kast.workspace.spi.SemanticReadAvailability
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseAdmission
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseAuthority
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseFailure
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseValidation
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host adapter from the existing indexer admission authority to the detached workspace SPI.
 */
internal class ExistingSemanticReadLeaseAuthority(
    private val delegate: WorkspaceSemanticReadAuthority,
    private val canonicalWorkspaceRoot: () -> CanonicalWorkspaceRoot,
) : SemanticReadLeaseAuthority {
    /**
     * Proof transition:
     * `WorkspaceSemanticReadAuthority -> SemanticReadLeaseAdmission`.
     *
     * Establishes an open lease bound to the same canonical root before and after legacy
     * admission, and to its exact published generation. [SemanticReadLeaseFailure] is the closed
     * expected failure. Legacy exceptions and Boolean currentness are consumed only in this outer
     * adapter.
     */
    override fun open(): SemanticReadLeaseAdmission {
        val rootBeforeAdmission = canonicalWorkspaceRoot()
        val token = try {
            delegate.openRead()
        } catch (_: IllegalStateException) {
            return SemanticReadLeaseAdmission.Rejected(unavailableFailure())
        }
        val rootAfterAdmission = canonicalWorkspaceRoot()
        if (rootBeforeAdmission != rootAfterAdmission) {
            token.close()
            return SemanticReadLeaseAdmission.Rejected(
                SemanticReadLeaseFailure.WorkspaceRootMoved(
                    expected = rootBeforeAdmission,
                    observed = rootAfterAdmission,
                ),
            )
        }
        return when (val generation = token.generation.evidenceGeneration()) {
            is Refinement.Rejected -> {
                token.close()
                SemanticReadLeaseAdmission.Rejected(
                    SemanticReadLeaseFailure.PublishedGenerationUnrepresentable(
                        token.generation.generation.value,
                    ),
                )
            }
            is Refinement.Refined ->
                SemanticReadLeaseAdmission.Admitted(
                    ExistingOpenSemanticReadLease(
                        delegate = delegate,
                        canonicalWorkspaceRoot = canonicalWorkspaceRoot,
                        token = token,
                        evidence = SemanticReadLease(
                            workspaceRoot = rootAfterAdmission,
                            generation = generation.value,
                        ),
                    ),
                )
        }
    }

    private fun unavailableFailure(): SemanticReadLeaseFailure.WorkspaceUnavailable =
        SemanticReadLeaseFailure.WorkspaceUnavailable(
            when (delegate.status()) {
                is IdeaIndexSemanticAdmission.Status.Failed -> SemanticReadAvailability.FAILED
                is IdeaIndexSemanticAdmission.Status.Pending,
                is IdeaIndexSemanticAdmission.Status.Ready,
                    -> SemanticReadAvailability.PENDING
            },
        )
}

private class ExistingOpenSemanticReadLease(
    private val delegate: WorkspaceSemanticReadAuthority,
    private val canonicalWorkspaceRoot: () -> CanonicalWorkspaceRoot,
    private val token: IdeaIndexSemanticAdmission.WorkspaceReadToken,
    override val evidence: SemanticReadLease,
) : OpenSemanticReadLease {
    private val closed = AtomicBoolean(false)

    override fun validate(): SemanticReadLeaseValidation {
        if (closed.get()) {
            return SemanticReadLeaseValidation.Rejected(
                SemanticReadLeaseFailure.LeaseClosed(evidence),
            )
        }
        val observedRoot = canonicalWorkspaceRoot()
        if (observedRoot != evidence.workspaceRoot) {
            return SemanticReadLeaseValidation.Rejected(
                SemanticReadLeaseFailure.WorkspaceRootMoved(
                    expected = evidence.workspaceRoot,
                    observed = observedRoot,
                ),
            )
        }
        if (delegate.isReadCurrent(token)) {
            return SemanticReadLeaseValidation.Current
        }
        return when (val status = delegate.status()) {
            is IdeaIndexSemanticAdmission.Status.Ready ->
                when (val observed = status.generation.evidenceGeneration()) {
                    is Refinement.Refined ->
                        SemanticReadLeaseValidation.Rejected(
                            SemanticReadLeaseFailure.PublishedGenerationMoved(
                                expected = evidence.generation,
                                observed = observed.value,
                            ),
                        )
                    is Refinement.Rejected ->
                        SemanticReadLeaseValidation.Rejected(
                            SemanticReadLeaseFailure.PublishedGenerationUnrepresentable(
                                status.generation.generation.value,
                            ),
                        )
                }
            is IdeaIndexSemanticAdmission.Status.Pending ->
                SemanticReadLeaseValidation.Rejected(
                    SemanticReadLeaseFailure.WorkspaceUnavailable(
                        SemanticReadAvailability.PENDING,
                    ),
                )
            is IdeaIndexSemanticAdmission.Status.Failed ->
                SemanticReadLeaseValidation.Rejected(
                    SemanticReadLeaseFailure.WorkspaceUnavailable(
                        SemanticReadAvailability.FAILED,
                    ),
                )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) token.close()
    }
}

/**
 * Proof transition:
 * `PublishedWorkspaceGenerationManifest -> Refinement<EvidenceGeneration, EvidenceGenerationFailure>`.
 *
 * Preserves the already positive published semantic generation as kernel evidence generation.
 * The kernel failure is closed. Raw generation extraction is permitted only in this legacy
 * adapter.
 */
private fun io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest.evidenceGeneration() =
    EvidenceGeneration.parse(generation.value)
