package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResourceBudget
import kotlin.reflect.KClass

/** Marker for a typed public operation request. */
interface OperationRequest

/** Marker for a typed successful operation payload. */
interface OperationPayload

/** Marker for a typed qualification attached to incomplete successful evidence. */
interface OperationQualification

/** Marker for an operation-owned closed rejection reason. */
interface OperationRejection

/**
 * Complete host-neutral metadata for one permanent public operation.
 *
 * This type deliberately contains no handler, function, runtime capability, or implementation
 * reference. Later composition may bind executable authority to [id] without weakening this
 * contract.
 */
data class OperationDefinition<
    Request : OperationRequest,
    Payload : OperationPayload,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    >(
    val id: OperationId,
    val requestType: KClass<Request>,
    val resultType: KClass<Payload>,
    val qualificationType: KClass<Qualification>,
    val rejectionType: KClass<Rejection>,
    val requiredCapability: CapabilityId,
    val effect: OperationEffect,
    val cost: OperationCost,
    val scope: OperationScope,
    val budget: ResourceBudget,
    val completeness: CompletenessPolicy,
) {
    /**
     * Proof transition: `EvidenceEnvelope<Payload> ->
     * OperationOutcomeBinding<Payload, Qualification, Rejection>`.
     *
     * Establishes that complete evidence names this definition's exact permanent [id].
     * [OperationOutcomeBindingFailure] is the closed expected failure. The raw payload may be
     * extracted only at an operation-specific external result boundary.
     */
    fun bindComplete(
        evidence: EvidenceEnvelope<Payload>,
    ): OperationOutcomeBinding<Payload, Qualification, Rejection> =
        if (evidence.operation == id) {
            OperationOutcomeBinding.Bound(OperationOutcome.Complete(evidence))
        } else {
            OperationOutcomeBinding.Rejected(
                OperationOutcomeBindingFailure.EvidenceOperationMismatch(
                    expected = id,
                    observed = evidence.operation,
                ),
            )
        }

    /**
     * Proof transition: `EvidenceEnvelope<Payload> + Qualification ->
     * OperationOutcomeBinding<Payload, Qualification, Rejection>`.
     *
     * Establishes that qualified evidence names this definition's exact permanent [id] while
     * preserving its typed qualification when [completeness] permits qualified success.
     * [OperationOutcomeBindingFailure] is the closed expected failure. The raw payload may be
     * extracted only at an operation-specific external result boundary.
     */
    fun bindQualified(
        evidence: EvidenceEnvelope<Payload>,
        qualification: Qualification,
    ): OperationOutcomeBinding<Payload, Qualification, Rejection> =
        if (evidence.operation != id) {
            OperationOutcomeBinding.Rejected(
                OperationOutcomeBindingFailure.EvidenceOperationMismatch(
                    expected = id,
                    observed = evidence.operation,
                ),
            )
        } else {
            when (completeness) {
                CompletenessPolicy.COMPLETE_REQUIRED ->
                    OperationOutcomeBinding.Rejected(
                        OperationOutcomeBindingFailure.QualificationNotAllowed(id),
                    )
                CompletenessPolicy.QUALIFIED_ALLOWED ->
                    OperationOutcomeBinding.Bound(
                        OperationOutcome.Qualified(evidence, qualification),
                    )
            }
        }

    fun reject(reason: Rejection): OperationOutcome<Payload, Qualification, Rejection> =
        OperationOutcome.Rejected(reason)
}

/**
 * Closed result of binding successful evidence to its declared permanent operation.
 */
sealed interface OperationOutcomeBinding<out Payload, out Qualification, out Rejection> {
    data class Bound<Payload, Qualification, Rejection>(
        val outcome: OperationOutcome<Payload, Qualification, Rejection>,
    ) : OperationOutcomeBinding<Payload, Qualification, Rejection>

    data class Rejected(
        val failure: OperationOutcomeBindingFailure,
    ) : OperationOutcomeBinding<Nothing, Nothing, Nothing>
}

sealed interface OperationOutcomeBindingFailure {
    data class EvidenceOperationMismatch(
        val expected: OperationId,
        val observed: OperationId,
    ) : OperationOutcomeBindingFailure

    data class QualificationNotAllowed(
        val operation: OperationId,
    ) : OperationOutcomeBindingFailure
}
