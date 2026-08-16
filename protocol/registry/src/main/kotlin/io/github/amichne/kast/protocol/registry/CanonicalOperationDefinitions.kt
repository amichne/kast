package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.CapabilityMarker
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.*
import kotlin.reflect.KClass

/** Sole production metadata catalog for the eleven canonical public operations. */
object CanonicalOperationDefinitions {
    val workspaceInspect = definition(
        CanonicalOperation.WORKSPACE_INSPECT,
        WorkspaceInspectRequest::class,
        WorkspaceInspectResult::class,
        WorkspaceInspectQualification::class,
        WorkspaceInspectRejection::class,
        WorkspaceInspectCapability::class,
        OperationLane.METADATA,
        OperationEffect.NONE,
        OperationCost.HOST_NEUTRAL,
        OperationScope.WORKSPACE,
        CompletenessPolicy.QUALIFIED_ALLOWED,
    )

    val symbolDiscover = definition(
        CanonicalOperation.SYMBOL_DISCOVER,
        SymbolDiscoverRequest::class,
        SymbolDiscoverResult::class,
        SymbolDiscoverQualification::class,
        SymbolDiscoverRejection::class,
        SymbolDiscoverCapability::class,
        OperationLane.INDEX_LOOKUP,
        OperationEffect.INTELLIJ_READ,
        OperationCost.BOUNDED_READ,
        OperationScope.WORKSPACE,
        CompletenessPolicy.QUALIFIED_ALLOWED,
    )

    val symbolResolve = definition(
        CanonicalOperation.SYMBOL_RESOLVE,
        SymbolResolveRequest::class,
        SymbolResolveResult::class,
        SymbolResolveQualification::class,
        SymbolResolveRejection::class,
        SymbolResolveCapability::class,
        OperationLane.SCOPED_SEMANTIC_READ,
        OperationEffect.INTELLIJ_READ,
        OperationCost.BOUNDED_READ,
        OperationScope.SYMBOL,
        CompletenessPolicy.COMPLETE_REQUIRED,
    )

    val symbolDescribe = definition(
        CanonicalOperation.SYMBOL_DESCRIBE,
        SymbolDescribeRequest::class,
        SymbolDescribeResult::class,
        SymbolDescribeQualification::class,
        SymbolDescribeRejection::class,
        SymbolDescribeCapability::class,
        OperationLane.SCOPED_SEMANTIC_READ,
        OperationEffect.INTELLIJ_READ,
        OperationCost.BOUNDED_READ,
        OperationScope.SYMBOL,
        CompletenessPolicy.COMPLETE_REQUIRED,
    )

    val relationRead = definition(
        CanonicalOperation.RELATION_READ,
        RelationReadRequest::class,
        RelationReadResult::class,
        RelationReadQualification::class,
        RelationReadRejection::class,
        RelationReadCapability::class,
        OperationLane.BOUNDED_RELATION_READ,
        OperationEffect.INTELLIJ_READ,
        OperationCost.BOUNDED_READ,
        OperationScope.SYMBOL,
        CompletenessPolicy.QUALIFIED_ALLOWED,
    )

    val traversalRun = definition(
        CanonicalOperation.TRAVERSAL_RUN,
        TraversalRunRequest::class,
        TraversalRunResult::class,
        TraversalRunQualification::class,
        TraversalRunRejection::class,
        TraversalRunCapability::class,
        OperationLane.REGISTERED_LONG_WORK,
        OperationEffect.NONE,
        OperationCost.BOUNDED_READ,
        OperationScope.SYMBOL,
        CompletenessPolicy.QUALIFIED_ALLOWED,
    )

    val diagnosticCheck = definition(
        CanonicalOperation.DIAGNOSTIC_CHECK,
        DiagnosticCheckRequest::class,
        DiagnosticCheckResult::class,
        DiagnosticCheckQualification::class,
        DiagnosticCheckRejection::class,
        DiagnosticCheckCapability::class,
        OperationLane.SCOPED_SEMANTIC_READ,
        OperationEffect.INTELLIJ_READ,
        OperationCost.BOUNDED_READ,
        OperationScope.PROJECT,
        CompletenessPolicy.QUALIFIED_ALLOWED,
    )

    val changePlan = definition(
        CanonicalOperation.CHANGE_PLAN,
        ChangePlanRequest::class,
        ChangePlanResult::class,
        ChangePlanQualification::class,
        ChangePlanRejection::class,
        ChangePlanCapability::class,
        OperationLane.DERIVED_WRITE,
        OperationEffect.NONE,
        OperationCost.BOUNDED_READ,
        OperationScope.SYMBOL,
        CompletenessPolicy.COMPLETE_REQUIRED,
    )

    val changeApply = definition(
        CanonicalOperation.CHANGE_APPLY,
        ChangeApplyRequest::class,
        ChangeApplyResult::class,
        ChangeApplyQualification::class,
        ChangeApplyRejection::class,
        ChangeApplyCapability::class,
        OperationLane.SOURCE_WRITE,
        OperationEffect.INTELLIJ_WRITE,
        OperationCost.PHYSICAL_EFFECT,
        OperationScope.FILE,
        CompletenessPolicy.COMPLETE_REQUIRED,
    )

    val changeVerify = definition(
        CanonicalOperation.CHANGE_VERIFY,
        ChangeVerifyRequest::class,
        ChangeVerifyResult::class,
        ChangeVerifyQualification::class,
        ChangeVerifyRejection::class,
        ChangeVerifyCapability::class,
        OperationLane.SCOPED_SEMANTIC_READ,
        OperationEffect.INTELLIJ_READ,
        OperationCost.BOUNDED_READ,
        OperationScope.SYMBOL,
        CompletenessPolicy.COMPLETE_REQUIRED,
    )

    val changeRecover = definition(
        CanonicalOperation.CHANGE_RECOVER,
        ChangeRecoverRequest::class,
        ChangeRecoverResult::class,
        ChangeRecoverQualification::class,
        ChangeRecoverRejection::class,
        ChangeRecoverCapability::class,
        OperationLane.SOURCE_WRITE,
        OperationEffect.INTELLIJ_WRITE,
        OperationCost.PHYSICAL_EFFECT,
        OperationScope.FILE,
        CompletenessPolicy.COMPLETE_REQUIRED,
    )

    val all: List<OperationDefinition<*, *, *, *, *>> = listOf(
        workspaceInspect,
        symbolDiscover,
        symbolResolve,
        symbolDescribe,
        relationRead,
        traversalRun,
        diagnosticCheck,
        changePlan,
        changeApply,
        changeVerify,
        changeRecover,
    )

    /** The one immutable production registry proven exact over [all]. */
    val registry: OperationRegistry = when (val construction = OperationRegistry.create(all)) {
        is OperationRegistryConstruction.Created -> construction.registry
        is OperationRegistryConstruction.Rejected ->
            error("Invalid canonical operation registry: ${construction.failures}")
    }

    private fun <
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
    ): OperationDefinition<Request, Result, Capability, Qualification, Rejection> =
        OperationDefinition(
            operation = operation,
            types = OperationTypeBinding(
                requestType = requestType,
                resultType = resultType,
                qualificationType = qualificationType,
                rejectionType = rejectionType,
                schema = schema("kast.${operation.id.value}.v1"),
            ),
            requiredCapability = capability(operation),
            capabilityType = capabilityType,
            lane = lane,
            effect = effect,
            cost = cost,
            scope = scope,
            budget = standardBudget(),
            completeness = completeness,
        )

    private fun capability(operation: CanonicalOperation): CapabilityId =
        refined(CapabilityId.parse("capability.${operation.id.value}"))

    private fun schema(raw: String): SchemaIdentity = refined(SchemaIdentity.parse(raw))

    private fun standardBudget(): ResourceBudget = ResourceBudget(
        resultLimit = refined(ResultLimit.parse(250)),
        workUnitLimit = refined(WorkUnitLimit.parse(10_000)),
        elapsedTimeLimit = refined(ElapsedTimeLimitMillis.parse(5_000)),
    )

    private fun <Strong, Failure> refined(value: Refinement<Strong, Failure>): Strong = when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("Invalid compile-time canonical operation metadata")
    }
}
