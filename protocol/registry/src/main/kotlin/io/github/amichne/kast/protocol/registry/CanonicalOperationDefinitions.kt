package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.CapabilityId
import io.github.amichne.kast.kernel.CapabilityMarker
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyCapability
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangePlanCapability
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverCapability
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeVerifyCapability
import io.github.amichne.kast.protocol.contract.ChangeVerifyQualification
import io.github.amichne.kast.protocol.contract.ChangeVerifyRejection
import io.github.amichne.kast.protocol.contract.ChangeVerifyRequest
import io.github.amichne.kast.protocol.contract.ChangeVerifyResult
import io.github.amichne.kast.protocol.contract.DiagnosticCheckCapability
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.OperationTypeBinding
import io.github.amichne.kast.protocol.contract.RelationReadCapability
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.contract.SymbolDescribeCapability
import io.github.amichne.kast.protocol.contract.SymbolDescribeQualification
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverCapability
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolResolveCapability
import io.github.amichne.kast.protocol.contract.SymbolResolveQualification
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.TraversalRunCapability
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TopologyBuildCapability
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.WorkspaceInspectCapability
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import kotlin.reflect.KClass

/** Sole production metadata catalog for the twelve canonical public operations. */
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
        HostedExposure.PUBLIC,
    )

    val topologyBuild = definition(
        CanonicalOperation.TOPOLOGY_BUILD,
        TopologyBuildRequest::class,
        TopologyBuildResult::class,
        TopologyBuildQualification::class,
        TopologyBuildRejection::class,
        TopologyBuildCapability::class,
        OperationLane.REGISTERED_LONG_WORK,
        OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
        OperationCost.PHYSICAL_EFFECT,
        OperationScope.WORKSPACE,
        CompletenessPolicy.COMPLETE_REQUIRED,
        HostedExposure.PUBLIC,
        schema = schema("kast.topology.build.v2"),
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
        HostedExposure.PUBLIC,
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
        HostedExposure.PUBLIC,
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
        HostedExposure.PUBLIC,
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
        HostedExposure.INTERNAL_ONLY,
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
        HostedExposure.PUBLIC,
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
        HostedExposure.INTERNAL_ONLY,
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
        HostedExposure.PUBLIC,
        hostedVariants = HostedVariants.Intents(setOf(HostedChangeIntent.ADD_DECLARATION)),
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
        HostedExposure.PUBLIC,
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
        HostedExposure.PUBLIC,
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
        HostedExposure.PUBLIC,
    )

    val all: List<OperationDefinition<*, *, *, *, *>> = listOf(
        workspaceInspect,
        topologyBuild,
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
        hostedExposure: HostedExposure,
        hostedVariants: HostedVariants = HostedVariants.None,
        schema: SchemaIdentity = schema("kast.${operation.id.value}.v2"),
    ): OperationDefinition<Request, Result, Capability, Qualification, Rejection> =
        OperationDefinition(
            operation = operation,
            types = OperationTypeBinding(
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
