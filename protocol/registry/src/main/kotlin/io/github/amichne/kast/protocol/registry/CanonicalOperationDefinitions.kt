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
import io.github.amichne.kast.protocol.contract.IndexSyncCapability
import io.github.amichne.kast.protocol.contract.IndexSyncQualification
import io.github.amichne.kast.protocol.contract.IndexSyncRejection
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.IndexSyncResult
import io.github.amichne.kast.protocol.contract.RelationReadCapability
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.contract.SourceReadCapability
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SymbolInspectCapability
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverCapability
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
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
import kotlin.reflect.KClass

/** Sole production metadata catalog for the eleven canonical public operations. */
object CanonicalOperationDefinitions {
    val indexSync = definition(
        CanonicalOperation.INDEX_SYNC,
        IndexSyncRequest::class,
        IndexSyncResult::class,
        IndexSyncQualification::class,
        IndexSyncRejection::class,
        IndexSyncCapability::class,
        OperationLane.REGISTERED_LONG_WORK,
        OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
        OperationCost.PHYSICAL_EFFECT,
        OperationScope.WORKSPACE,
        CompletenessPolicy.COMPLETE_REQUIRED,
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

    val symbolInspect = definition(
        CanonicalOperation.SYMBOL_INSPECT,
        SymbolInspectRequest::class,
        SymbolInspectResult::class,
        SymbolInspectQualification::class,
        SymbolInspectRejection::class,
        SymbolInspectCapability::class,
        OperationLane.SCOPED_SEMANTIC_READ,
        OperationEffect.INTELLIJ_READ,
        OperationCost.BOUNDED_READ,
        OperationScope.SYMBOL,
        CompletenessPolicy.COMPLETE_REQUIRED,
        HostedExposure.PUBLIC,
    )

    val sourceRead = definition(
        CanonicalOperation.SOURCE_READ,
        SourceReadRequest::class,
        SourceReadResult::class,
        SourceReadQualification::class,
        SourceReadRejection::class,
        SourceReadCapability::class,
        OperationLane.SCOPED_SEMANTIC_READ,
        OperationEffect.INTELLIJ_READ,
        OperationCost.BOUNDED_READ,
        OperationScope.SOURCE,
        CompletenessPolicy.QUALIFIED_ALLOWED,
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
        HostedExposure.PUBLIC,
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
        HostedExposure.PUBLIC,
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
        indexSync,
        topologyBuild,
        symbolDiscover,
        symbolInspect,
        sourceRead,
        relationRead,
        traversalRun,
        diagnosticCheck,
        changePlan,
        changeApply,
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
