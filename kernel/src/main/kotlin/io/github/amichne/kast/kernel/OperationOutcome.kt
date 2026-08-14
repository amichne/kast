package io.github.amichne.kast.kernel

/**
 * Exhaustive semantic result independent of transport success.
 *
 * [Complete] and [Qualified] retain generation-bound evidence. [Rejected] contains only the
 * operation-owned closed rejection value, so absence of evidence cannot be presented as success.
 */
sealed interface OperationOutcome<out Payload, out Qualification, out Rejection> {
    data class Complete<Payload>(
        val evidence: EvidenceEnvelope<Payload>,
    ) : OperationOutcome<Payload, Nothing, Nothing>

    data class Qualified<Payload, Qualification>(
        val evidence: EvidenceEnvelope<Payload>,
        val qualification: Qualification,
    ) : OperationOutcome<Payload, Qualification, Nothing>

    data class Rejected<Rejection>(
        val reason: Rejection,
    ) : OperationOutcome<Nothing, Nothing, Rejection>
}
