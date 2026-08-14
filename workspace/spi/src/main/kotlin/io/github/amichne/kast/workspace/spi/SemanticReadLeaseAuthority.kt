package io.github.amichne.kast.workspace.spi

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRootFailure
import io.github.amichne.kast.workspace.contract.SemanticReadLease

/**
 * Finite reasons that semantic work cannot return under its admitted lease.
 */
sealed interface SemanticReadLeaseFailure {
    data object DumbMode : SemanticReadLeaseFailure

    data object TransitionInProgress : SemanticReadLeaseFailure

    data object WorkspaceBlocked : SemanticReadLeaseFailure

    data class WorkspaceRootMoved(
        val expected: CanonicalWorkspaceRoot,
        val observed: CanonicalWorkspaceRoot,
    ) : SemanticReadLeaseFailure

    data class PublishedGenerationMoved(
        val expected: EvidenceGeneration,
        val observed: EvidenceGeneration,
    ) : SemanticReadLeaseFailure

    data class PublishedGenerationUnrepresentable(
        val observed: Long,
    ) : SemanticReadLeaseFailure

    data class WorkspaceRootUnrepresentable(
        val failure: CanonicalWorkspaceRootFailure,
    ) : SemanticReadLeaseFailure

    data class LeaseClosed(
        val lease: SemanticReadLease,
    ) : SemanticReadLeaseFailure
}

/**
 * Strong capability for one admitted semantic read.
 *
 * Implementations may retain private adapter handles required for validation and release, but the
 * only exposed state is detached [evidence].
 */
interface OpenSemanticReadLease : AutoCloseable {
    val evidence: SemanticReadLease

    /**
     * Proof transition: `OpenSemanticReadLease -> SemanticReadLeaseValidation`.
     *
     * Establishes that the exact root and publication captured in [evidence] remain current.
     * [SemanticReadLeaseFailure] is the closed expected failure. Physical currentness observation
     * is permitted only inside the owning adapter.
     */
    fun validate(): SemanticReadLeaseValidation
}

sealed interface SemanticReadLeaseAdmission {
    data class Admitted(
        val lease: OpenSemanticReadLease,
    ) : SemanticReadLeaseAdmission

    data class Rejected(
        val failure: SemanticReadLeaseFailure,
    ) : SemanticReadLeaseAdmission
}

sealed interface SemanticReadLeaseValidation {
    data object Current : SemanticReadLeaseValidation

    data class Rejected(
        val failure: SemanticReadLeaseFailure,
    ) : SemanticReadLeaseValidation
}

fun interface SemanticReadLeaseAuthority {
    /**
     * Proof transition:
     * `(SemanticReadLeaseAuthority, SemanticReadFreshnessRequirement) -> SemanticReadLeaseAdmission`.
     *
     * Establishes a strong open lease fixing one canonical root and published generation.
     * [SemanticReadLeaseFailure] is the closed expected failure. Raw runtime admission state may be
     * observed only by the implementation adapter.
     */
    fun open(requirement: SemanticReadFreshnessRequirement): SemanticReadLeaseAdmission
}
