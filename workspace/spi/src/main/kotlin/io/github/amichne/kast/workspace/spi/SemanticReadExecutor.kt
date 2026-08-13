package io.github.amichne.kast.workspace.spi

import io.github.amichne.kast.workspace.contract.SemanticReadLease

/**
 * Closed semantic execution outcome after post-operation lease validation.
 */
sealed interface SemanticReadExecution<out Payload> {
    data class Completed<Payload>(
        val lease: SemanticReadLease,
        val payload: Payload,
    ) : SemanticReadExecution<Payload>

    data class Rejected(
        val failure: SemanticReadAdmissionFailure,
    ) : SemanticReadExecution<Nothing>
}

sealed interface SemanticReadAdmissionFailure {
    data class RuntimeUnavailable(
        val failure: RuntimeLivenessFailure,
    ) : SemanticReadAdmissionFailure

    data class SemanticUnavailable(
        val failure: SemanticReadLeaseFailure,
    ) : SemanticReadAdmissionFailure
}

class SemanticReadExecutor(
    private val runtimeLiveness: RuntimeLivenessAuthority,
    private val authority: SemanticReadLeaseAuthority,
) {
    /**
     * Proof transition:
     * `(RuntimeLivenessAuthority, SemanticReadLeaseAuthority, SemanticReadFreshnessRequirement,`
     * `suspend (SemanticReadLease -> Payload))`
     * `-> SemanticReadExecution<Payload>`.
     *
     * Establishes bounded runtime liveness before opening a lease, then establishes that
     * [SemanticReadExecution.Completed] was produced and revalidated under the same canonical root
     * and published generation. [SemanticReadAdmissionFailure] is the closed expected failure; a
     * computed payload is discarded when validation rejects it. Raw operation results may be
     * extracted only after the completed adapter boundary.
     */
    suspend fun <Payload> current(
        freshness: SemanticReadFreshnessRequirement = SemanticReadFreshnessRequirement.SMART_INDEXES,
        operation: suspend (SemanticReadLease) -> Payload,
    ): SemanticReadExecution<Payload> = when (val liveness = runtimeLiveness.admit()) {
        is RuntimeLivenessAdmission.Rejected ->
            SemanticReadExecution.Rejected(
                SemanticReadAdmissionFailure.RuntimeUnavailable(liveness.failure),
            )
        RuntimeLivenessAdmission.Live -> when (val admission = authority.open(freshness)) {
            is SemanticReadLeaseAdmission.Rejected ->
                SemanticReadExecution.Rejected(
                    SemanticReadAdmissionFailure.SemanticUnavailable(admission.failure),
                )
            is SemanticReadLeaseAdmission.Admitted ->
                admission.lease.use { lease ->
                    val payload = operation(lease.evidence)
                    when (val validation = lease.validate()) {
                        SemanticReadLeaseValidation.Current ->
                            SemanticReadExecution.Completed(lease.evidence, payload)
                        is SemanticReadLeaseValidation.Rejected ->
                            SemanticReadExecution.Rejected(
                                SemanticReadAdmissionFailure.SemanticUnavailable(validation.failure),
                            )
                    }
                }
        }
    }
}
