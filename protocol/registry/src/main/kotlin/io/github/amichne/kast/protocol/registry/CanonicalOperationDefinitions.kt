package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyCapability
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeCapability
import io.github.amichne.kast.protocol.contract.ChangePlanCapability
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverCapability
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import io.github.amichne.kast.protocol.contract.ChangeRejection
import io.github.amichne.kast.protocol.contract.ChangeRequest
import io.github.amichne.kast.protocol.contract.ChangeResult
import io.github.amichne.kast.protocol.contract.DiagnosticCheckCapability
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckQualification
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckResult
import io.github.amichne.kast.protocol.contract.IndexSyncCapability
import io.github.amichne.kast.protocol.contract.IndexSyncQualification
import io.github.amichne.kast.protocol.contract.IndexSyncRejection
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.IndexSyncResult
import io.github.amichne.kast.protocol.contract.QueryRunCapability
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationReadCapability
import io.github.amichne.kast.protocol.contract.RelationReadFailure
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceReadCapability
import io.github.amichne.kast.protocol.contract.SourceReadFailure
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverCapability
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolInspectCapability
import io.github.amichne.kast.protocol.contract.SymbolInspectQualification
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectResult
import io.github.amichne.kast.protocol.contract.TopologyBuildCapability
import io.github.amichne.kast.protocol.contract.TopologyBuildQualification
import io.github.amichne.kast.protocol.contract.TopologyBuildRejection
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildResult
import io.github.amichne.kast.protocol.contract.TraversalRunCapability
import io.github.amichne.kast.protocol.contract.TraversalRunFailure
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult

/** Sole metadata catalog for canonical operations and their explicit publication authority. */
object CanonicalOperationDefinitions {
    val workspaceLifecycle =
        definition(
            CanonicalOperation.WORKSPACE_LIFECYCLE,
            io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest::class,
            io.github.amichne.kast.protocol.contract.IdeLifecycleResult::class,
            io.github.amichne.kast.protocol.contract.IdeLifecycleQualification::class,
            io.github.amichne.kast.protocol.contract.IdeLifecycleFailure::class,
            io.github.amichne.kast.protocol.contract.IdeLifecycleCapability::class,
            OperationLane.REGISTERED_LONG_WORK,
            OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
            OperationCost.PHYSICAL_EFFECT,
            OperationScope.WORKSPACE,
            CompletenessPolicy.QUALIFIED_ALLOWED,
            HostedExposure.PUBLIC,
        )
    val indexSync =
        definition(
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
            HostedExposure.INTERNAL_ONLY,
        )

    val topologyBuild =
        definition(
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
            HostedExposure.INTERNAL_ONLY,
            schema = schema("kast.topology.build.v2"),
        )

    val symbolDiscover =
        definition(
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
            schema = schema("kast.symbol.discover.v3"),
        )

    val symbolInspect =
        definition(
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
            schema = schema("kast.symbol.inspect.v4"),
        )

    val sourceRead =
        definition(
            CanonicalOperation.SOURCE_READ,
            SourceReadRequest::class,
            SourceReadResult::class,
            SourceReadQualification::class,
            SourceReadFailure::class,
            SourceReadCapability::class,
            OperationLane.SCOPED_SEMANTIC_READ,
            OperationEffect.INTELLIJ_READ,
            OperationCost.BOUNDED_READ,
            OperationScope.SOURCE,
            CompletenessPolicy.QUALIFIED_ALLOWED,
            HostedExposure.PUBLIC,
            schema = schema("kast.source.read.v5"),
        )

    val relationRead =
        definition(
            CanonicalOperation.RELATION_READ,
            RelationReadRequest::class,
            RelationReadResult::class,
            RelationReadQualification::class,
            RelationReadFailure::class,
            RelationReadCapability::class,
            OperationLane.BOUNDED_RELATION_READ,
            OperationEffect.INTELLIJ_READ,
            OperationCost.BOUNDED_READ,
            OperationScope.SYMBOL,
            CompletenessPolicy.QUALIFIED_ALLOWED,
            HostedExposure.PUBLIC,
            schema = schema("kast.relation.read.v3"),
        )

    val traversalRun =
        definition(
            CanonicalOperation.TRAVERSAL_RUN,
            TraversalRunRequest::class,
            TraversalRunResult::class,
            TraversalRunQualification::class,
            TraversalRunFailure::class,
            TraversalRunCapability::class,
            OperationLane.REGISTERED_LONG_WORK,
            OperationEffect.NONE,
            OperationCost.BOUNDED_READ,
            OperationScope.SYMBOL,
            CompletenessPolicy.QUALIFIED_ALLOWED,
            HostedExposure.PUBLIC,
            schema = schema("kast.traversal.run.v3"),
        )

    val queryRun =
        definition(
            CanonicalOperation.QUERY_RUN,
            QueryRunRequest::class,
            QueryRunResult::class,
            QueryRunQualification::class,
            QueryRunFailure::class,
            QueryRunCapability::class,
            OperationLane.SCOPED_SEMANTIC_READ,
            OperationEffect.INTELLIJ_READ,
            OperationCost.BOUNDED_READ,
            OperationScope.WORKSPACE,
            CompletenessPolicy.QUALIFIED_ALLOWED,
            HostedExposure.PUBLIC,
            schema = schema("kast.query.run.v2"),
        )

    val diagnosticCheck =
        definition(
            CanonicalOperation.DIAGNOSTIC_CHECK,
            DiagnosticCheckRequest::class,
            DiagnosticCheckResult::class,
            DiagnosticCheckQualification::class,
            DiagnosticCheckFailure::class,
            DiagnosticCheckCapability::class,
            OperationLane.SCOPED_SEMANTIC_READ,
            OperationEffect.INTELLIJ_READ,
            OperationCost.BOUNDED_READ,
            OperationScope.PROJECT,
            CompletenessPolicy.QUALIFIED_ALLOWED,
            HostedExposure.PUBLIC,
            schema = schema("kast.diagnostic.check.v4"),
        )

    val change =
        definition(
            CanonicalOperation.CHANGE,
            ChangeRequest::class,
            ChangeResult::class,
            ChangeQualification::class,
            ChangeRejection::class,
            ChangeCapability::class,
            OperationLane.SOURCE_WRITE,
            OperationEffect.INTELLIJ_WRITE,
            OperationCost.PHYSICAL_EFFECT,
            OperationScope.FILE,
            CompletenessPolicy.COMPLETE_REQUIRED,
            HostedExposure.PUBLIC,
            hostedVariants = HostedVariants.Intents(setOf(HostedChangeIntent.ADD_DECLARATION)),
        )

    val changePlan =
        definition(
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

    val changeApply =
        definition(
            operation = CanonicalOperation.CHANGE_APPLY,
            requestType = ChangeApplyRequest::class,
            resultType = ChangeApplyResult::class,
            qualificationType = ChangeApplyQualification::class,
            rejectionType = ChangeApplyRejection::class,
            capabilityType = ChangeApplyCapability::class,
            lane = OperationLane.SOURCE_WRITE,
            effect = OperationEffect.INTELLIJ_WRITE,
            cost = OperationCost.PHYSICAL_EFFECT,
            scope = OperationScope.FILE,
            completeness = CompletenessPolicy.COMPLETE_REQUIRED,
            hostedExposure = HostedExposure.PUBLIC,
            schema = schema("kast.change.apply.v3"),
        )

    val changeRecover =
        definition(
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

    val all: List<OperationDefinition<*, *, *, *, *>> =
        listOf(
            workspaceLifecycle,
            indexSync,
            topologyBuild,
            queryRun,
            symbolDiscover,
            symbolInspect,
            sourceRead,
            relationRead,
            traversalRun,
            diagnosticCheck,
            change,
            changePlan,
            changeApply,
            changeRecover,
        )

    /** The one immutable production registry proven exact over [all]. */
    val registry: OperationRegistry =
        when (val construction = OperationRegistry.create(all)) {
            is OperationRegistryConstruction.Created -> construction.registry
            is OperationRegistryConstruction.Rejected ->
                error("Invalid canonical operation registry: ${construction.failures}")
        }
}
