package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.idea.IdeaIndexSemanticAdmission
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.spi.OpenSemanticReadLease
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseAdmission
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseAuthority
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseFailure
import io.github.amichne.kast.workspace.spi.SemanticReadLeaseValidation
import io.github.amichne.kast.workspace.spi.SemanticReadFreshness
import io.github.amichne.kast.workspace.spi.SemanticReadFreshnessAuthority
import io.github.amichne.kast.workspace.spi.SemanticReadFreshnessRequirement
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host adapter from the existing indexer admission authority to the detached workspace SPI.
 */
internal class ExistingSemanticReadLeaseAuthority(
    private val delegate: WorkspaceSemanticReadAuthority,
    private val workspaceRootPath: () -> Path,
    private val freshness: SemanticReadFreshnessAuthority,
) : SemanticReadLeaseAuthority {
    /**
     * Proof transition:
     * `(WorkspaceSemanticReadAuthority, SemanticReadFreshnessAuthority,`
     * `SemanticReadFreshnessRequirement) -> SemanticReadLeaseAdmission`.
     *
     * Establishes an open lease bound to the same canonical root before and after legacy
     * admission, and to its exact published generation. [SemanticReadLeaseFailure] is the closed
     * expected failure. Legacy exceptions and Boolean currentness are consumed only in this outer
     * adapter.
     */
    override fun open(requirement: SemanticReadFreshnessRequirement): SemanticReadLeaseAdmission {
        freshness.failureOrNull(requirement)?.let { failure ->
            return SemanticReadLeaseAdmission.Rejected(failure)
        }
        val rootBeforeAdmission = when (
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(workspaceRootPath())
        ) {
            is Refinement.Refined -> root.value
            is Refinement.Rejected ->
                return SemanticReadLeaseAdmission.Rejected(
                    SemanticReadLeaseFailure.WorkspaceRootUnrepresentable(root.failure),
                )
        }
        val token = try {
            delegate.openRead()
        } catch (_: IllegalStateException) {
            return SemanticReadLeaseAdmission.Rejected(
                freshness.failureOrNull(requirement) ?: SemanticReadLeaseFailure.TransitionInProgress,
            )
        }
        val rootAfterAdmission = when (
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(workspaceRootPath())
        ) {
            is Refinement.Refined -> root.value
            is Refinement.Rejected -> {
                token.close()
                return SemanticReadLeaseAdmission.Rejected(
                    SemanticReadLeaseFailure.WorkspaceRootUnrepresentable(root.failure),
                )
            }
        }
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
                        workspaceRootPath = workspaceRootPath,
                        freshness = freshness,
                        freshnessRequirement = requirement,
                        token = token,
                        evidence = SemanticReadLease(
                            workspaceRoot = rootAfterAdmission,
                            generation = generation.value,
                        ),
                    ),
                )
        }
    }
}

private class ExistingOpenSemanticReadLease(
    private val delegate: WorkspaceSemanticReadAuthority,
    private val workspaceRootPath: () -> Path,
    private val freshness: SemanticReadFreshnessAuthority,
    private val freshnessRequirement: SemanticReadFreshnessRequirement,
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
        freshness.failureOrNull(freshnessRequirement)?.let { failure ->
            return SemanticReadLeaseValidation.Rejected(failure)
        }
        val observedRoot = when (
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(workspaceRootPath())
        ) {
            is Refinement.Refined -> root.value
            is Refinement.Rejected ->
                return SemanticReadLeaseValidation.Rejected(
                    SemanticReadLeaseFailure.WorkspaceRootUnrepresentable(root.failure),
                )
        }
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
                    SemanticReadLeaseFailure.TransitionInProgress,
                )
            is IdeaIndexSemanticAdmission.Status.Failed ->
                SemanticReadLeaseValidation.Rejected(
                    SemanticReadLeaseFailure.WorkspaceBlocked,
                )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) token.close()
    }
}

private fun SemanticReadFreshnessAuthority.failureOrNull(
    requirement: SemanticReadFreshnessRequirement,
): SemanticReadLeaseFailure? = when (observe()) {
    SemanticReadFreshness.Ready -> null
    SemanticReadFreshness.DumbMode -> when (requirement) {
        SemanticReadFreshnessRequirement.SMART_INDEXES -> SemanticReadLeaseFailure.DumbMode
        SemanticReadFreshnessRequirement.QUALIFIED_DUMB_MODE -> null
    }
    SemanticReadFreshness.TransitionInProgress -> SemanticReadLeaseFailure.TransitionInProgress
    SemanticReadFreshness.WorkspaceBlocked -> SemanticReadLeaseFailure.WorkspaceBlocked
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
