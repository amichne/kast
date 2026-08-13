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
        val failure: SemanticReadLeaseFailure,
    ) : SemanticReadExecution<Nothing>
}

class SemanticReadExecutor(
    private val authority: SemanticReadLeaseAuthority,
) {
    /**
     * Proof transition: `SemanticReadLeaseAuthority + suspend (SemanticReadLease -> Payload) ->
     * SemanticReadExecution<Payload>`.
     *
     * Establishes that [SemanticReadExecution.Completed] was produced and revalidated under the
     * same canonical root and published generation. [SemanticReadLeaseFailure] is the closed
     * expected failure; a computed payload is discarded when validation rejects it. Raw operation
     * results may be extracted only after the completed adapter boundary.
     */
    suspend fun <Payload> current(
        operation: suspend (SemanticReadLease) -> Payload,
    ): SemanticReadExecution<Payload> = when (val admission = authority.open()) {
        is SemanticReadLeaseAdmission.Rejected ->
            SemanticReadExecution.Rejected(admission.failure)
        is SemanticReadLeaseAdmission.Admitted ->
            admission.lease.use { lease ->
                val payload = operation(lease.evidence)
                when (val validation = lease.validate()) {
                    SemanticReadLeaseValidation.Current ->
                        SemanticReadExecution.Completed(lease.evidence, payload)
                    is SemanticReadLeaseValidation.Rejected ->
                        SemanticReadExecution.Rejected(validation.failure)
                }
            }
    }
}
