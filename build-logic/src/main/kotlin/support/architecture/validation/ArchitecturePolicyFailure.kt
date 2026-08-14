package support.architecture

import support.architecture.process.MutationRuntimeProcessId
import support.architecture.process.MutationRuntimeTopologyFailure

sealed interface ArchitecturePolicyFailure {
    data class DuplicateModule(val id: ModuleId) : ArchitecturePolicyFailure

    data class MissingModuleDependency(
        val module: ModuleId,
        val missing: ModuleId,
    ) : ArchitecturePolicyFailure

    data class ForbiddenModuleRoleDependency(
        val module: ModuleId,
        val dependency: ModuleId,
        val dependencyRole: ModuleRole,
    ) : ArchitecturePolicyFailure

    data class ForbiddenModuleCostDependency(
        val module: ModuleId,
        val dependency: ModuleId,
        val dependencyCost: ModuleCost,
    ) : ArchitecturePolicyFailure

    data class ForbiddenModuleRoleEffect(
        val module: ModuleId,
        val effect: ForbiddenEffect,
    ) : ArchitecturePolicyFailure

    data class FeatureContractDependsOnRegistry(
        val featureContract: ModuleId,
    ) : ArchitecturePolicyFailure

    data object MissingRuntimeComposition : ArchitecturePolicyFailure

    data class UnexpectedCompositionOwner(
        val module: ModuleId,
    ) : ArchitecturePolicyFailure

    data class InvalidRuntimeCompositionDependencies(
        val missing: Set<ModuleId>,
        val unexpected: Set<ModuleId>,
    ) : ArchitecturePolicyFailure

    data class ModuleDependencyCycle(val members: Set<ModuleId>) : ArchitecturePolicyFailure

    data class DuplicateMutationDeliveryTask(val id: MutationDeliveryTaskId) : ArchitecturePolicyFailure

    data class MissingMutationDeliveryDependency(
        val task: MutationDeliveryTaskId,
        val missing: MutationDeliveryTaskId,
    ) : ArchitecturePolicyFailure

    data class MissingMutationDeliveryOwnerModule(
        val task: MutationDeliveryTaskId,
        val missing: ModuleId,
    ) : ArchitecturePolicyFailure

    data class MutationDeliveryDependencyCycle(
        val members: Set<MutationDeliveryTaskId>,
    ) : ArchitecturePolicyFailure

    data class DuplicateLegacyAllowance(
        val violation: LegacyViolationKey,
    ) : ArchitecturePolicyFailure

    data class MissingLegacyRetirementTask(
        val allowance: LegacyAllowance,
    ) : ArchitecturePolicyFailure

    data class MissingLegacyAllowanceModule(
        val allowance: LegacyAllowance,
        val missing: ModuleId,
    ) : ArchitecturePolicyFailure

    data class NonExactLegacyAllowance(
        val allowance: LegacyAllowance,
    ) : ArchitecturePolicyFailure

    data class DependencyAllowanceRequiresMigration(
        val allowance: LegacyAllowance,
    ) : ArchitecturePolicyFailure

    data class DuplicateLegacyMigration(
        val dependency: ProjectDependencyObservation,
    ) : ArchitecturePolicyFailure

    data class UnadmittedLegacyMigration(
        val migration: LegacyMigrationEdgePolicy,
    ) : ArchitecturePolicyFailure

    data class InvalidLegacyMigrationTarget(
        val migration: LegacyMigrationEdgePolicy,
        val failures: Set<LegacyMigrationTargetFailure>,
    ) : ArchitecturePolicyFailure

    data class MissingLegacyMigrationModule(
        val migration: LegacyMigrationEdgePolicy,
        val missing: ModuleId,
    ) : ArchitecturePolicyFailure

    data class InvalidLegacyMigrationDirection(
        val migration: LegacyMigrationEdgePolicy,
    ) : ArchitecturePolicyFailure

    data class CompletedLegacyMigration(
        val migration: LegacyMigrationEdgePolicy,
    ) : ArchitecturePolicyFailure

    data class MissingLegacyMigrationRetirementTask(
        val migration: LegacyMigrationEdgePolicy,
    ) : ArchitecturePolicyFailure

    data class CompletedLegacyMigrationRetirementTask(
        val migration: LegacyMigrationEdgePolicy,
    ) : ArchitecturePolicyFailure

    data class PermanentLegacyMigration(
        val migration: LegacyMigrationEdgePolicy,
    ) : ArchitecturePolicyFailure

    data class DuplicateLegacyImplementationBridge(
        val dependency: ProjectDependencyObservation,
    ) : ArchitecturePolicyFailure

    data class UnadmittedLegacyImplementationBridge(
        val bridge: LegacyImplementationBridgePolicy,
    ) : ArchitecturePolicyFailure

    data class MissingLegacyImplementationBridgeModule(
        val bridge: LegacyImplementationBridgePolicy,
        val missing: ModuleId,
    ) : ArchitecturePolicyFailure

    data class InvalidLegacyImplementationBridgeDirection(
        val bridge: LegacyImplementationBridgePolicy,
    ) : ArchitecturePolicyFailure

    data class CompletedLegacyImplementationBridge(
        val bridge: LegacyImplementationBridgePolicy,
    ) : ArchitecturePolicyFailure

    data class MissingLegacyImplementationBridgeRetirementTask(
        val bridge: LegacyImplementationBridgePolicy,
    ) : ArchitecturePolicyFailure

    data class CompletedLegacyImplementationBridgeRetirementTask(
        val bridge: LegacyImplementationBridgePolicy,
    ) : ArchitecturePolicyFailure

    data class InvalidLegacyImplementationBridgeRetirementOwner(
        val bridge: LegacyImplementationBridgePolicy,
        val owner: MutationDeliveryOwner,
    ) : ArchitecturePolicyFailure

    data class InvalidLegacyImplementationBridgeRetirementPhase(
        val bridge: LegacyImplementationBridgePolicy,
        val phase: MutationDeliveryPhase,
    ) : ArchitecturePolicyFailure

    data class PermanentLegacyImplementationBridge(
        val bridge: LegacyImplementationBridgePolicy,
    ) : ArchitecturePolicyFailure

    data class DuplicateMutationRuntimeProcess(val id: MutationRuntimeProcessId) : ArchitecturePolicyFailure

    data class MissingMutationRuntimeProcessDependency(
        val process: MutationRuntimeProcessId,
        val missing: MutationRuntimeProcessId,
    ) : ArchitecturePolicyFailure

    data class MissingMutationRuntimeProcessOwner(
        val process: MutationRuntimeProcessId,
        val missing: ModuleId,
    ) : ArchitecturePolicyFailure

    data class MutationRuntimeProcessDependencyCycle(
        val members: Set<MutationRuntimeProcessId>,
    ) : ArchitecturePolicyFailure

    data class InvalidMutationRuntimeTopology(
        val failure: MutationRuntimeTopologyFailure,
    ) : ArchitecturePolicyFailure
}
