package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.CapabilityMarker
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.OperationTypeBinding
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import kotlin.reflect.KClass

private const val STANDARD_RESULT_LIMIT = 250
private const val STANDARD_WORK_UNIT_LIMIT = 10_000L
private const val STANDARD_ELAPSED_MILLIS = 5_000L

/** Constructs typed canonical definitions with one shared budget and identity policy. */
internal fun <
    Request : OperationRequest,
    Result : OperationResult,
    Qualification : OperationQualification,
    Rejection : OperationRejection,
    Capability : CapabilityMarker,
> definition(
    operation: CanonicalOperation,
    requestType: KClass<Request>,
    resultType: KClass<Result>,
    qualificationType: KClass<Qualification>,
    rejectionType: KClass<Rejection>,
    capabilityType: KClass<Capability>,
    lane: OperationLane,
    effect: OperationEffect,
    cost: OperationCost,
    scope: OperationScope,
    completeness: CompletenessPolicy,
    hostedExposure: HostedExposure,
    hostedVariants: HostedVariants = HostedVariants.None,
    schema: SchemaIdentity = schema("kast.${operation.id.value}.v2"),
): OperationDefinition<Request, Result, Capability, Qualification, Rejection> =
    OperationDefinition(
        operation = operation,
        types =
            OperationTypeBinding(
                requestType = requestType,
                resultType = resultType,
                qualificationType = qualificationType,
                rejectionType = rejectionType,
                schema = schema,
            ),
        requiredCapability = capability(operation),
        capabilityType = capabilityType,
        lane = lane,
        effect = effect,
        cost = cost,
        scope = scope,
        budget = standardBudget(),
        completeness = completeness,
        hostedExposure = hostedExposure,
        hostedVariants = hostedVariants,
    )

private fun capability(operation: CanonicalOperation): CapabilityId =
    refined(CapabilityId.parse("capability.${operation.id.value}"))

internal fun schema(raw: String): SchemaIdentity = refined(SchemaIdentity.parse(raw))

private fun standardBudget(): ResourceBudget =
    ResourceBudget(
        resultLimit = refined(ResultLimit.parse(STANDARD_RESULT_LIMIT)),
        workUnitLimit = refined(WorkUnitLimit.parse(STANDARD_WORK_UNIT_LIMIT)),
        elapsedTimeLimit = refined(ElapsedTimeLimitMillis.parse(STANDARD_ELAPSED_MILLIS)),
    )

private fun <Strong, Failure> refined(value: Refinement<Strong, Failure>): Strong =
    when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("Invalid compile-time canonical operation metadata")
    }
