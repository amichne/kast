package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.CapabilityMarker
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.OperationTypeBinding
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import kotlin.reflect.KClass

/**
 * Complete host-neutral metadata for one permanent public operation.
 *
 * This type deliberately contains no handler, function, runtime capability, or implementation
 * reference. Later composition may bind executable authority to [id] without weakening this
 * contract.
 */
data class OperationDefinition<
    Request : OperationRequest,
    Result : OperationResult,
    Capability : CapabilityMarker,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    >(
    val operation: CanonicalOperation,
    val types: OperationTypeBinding<Request, Result, Qualification, Rejection>,
    val requiredCapability: CapabilityId,
    val capabilityType: KClass<Capability>,
    val lane: OperationLane,
    val effect: OperationEffect,
    val cost: OperationCost,
    val scope: OperationScope,
    val budget: ResourceBudget,
    val completeness: CompletenessPolicy,
    val hostedExposure: HostedExposure,
    val hostedVariants: HostedVariants = HostedVariants.None,
) : OperationMetadata {
    val executionBudget: OperationExecutionBudget
        get() = OperationExecutionBudget.forOperation(operation)

    override val id: OperationId
        get() = operation.id

    val requestType: KClass<Request>
        get() = types.requestType

    val resultType: KClass<Result>
        get() = types.resultType

    val qualificationType: KClass<Qualification>
        get() = types.qualificationType

    val rejectionType: KClass<Rejection>
        get() = types.rejectionType

    val schema: SchemaIdentity
        get() = types.schema

    /**
     * Proof transition: `EvidenceEnvelope<Result> ->
     * OperationOutcomeBinding<Result, Qualification, Rejection>`.
     *
     * Establishes that complete evidence names this definition's exact permanent [id].
     * [OperationOutcomeBindingFailure] is the closed expected failure. The raw payload may be
     * extracted only at an operation-specific external result boundary.
     */
    fun bindComplete(
        evidence: EvidenceEnvelope<Result>,
    ): OperationOutcomeBinding<Result, Qualification, Rejection> =
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
     * Proof transition: `EvidenceEnvelope<Result> + Qualification ->
     * OperationOutcomeBinding<Result, Qualification, Rejection>`.
     *
     * Establishes that qualified evidence names this definition's exact permanent [id] while
     * preserving its typed qualification when [completeness] permits qualified success.
     * [OperationOutcomeBindingFailure] is the closed expected failure. The raw payload may be
     * extracted only at an operation-specific external result boundary.
     */
    fun bindQualified(
        evidence: EvidenceEnvelope<Result>,
        qualification: Qualification,
    ): OperationOutcomeBinding<Result, Qualification, Rejection> =
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

    fun reject(reason: Rejection): OperationOutcome<Result, Qualification, Rejection> =
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
