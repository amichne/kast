package support.architecture

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

    data class FeatureContractDependsOnRegistry(val featureContract: ModuleId) : ArchitecturePolicyFailure

    data object MissingRuntimeComposition : ArchitecturePolicyFailure

    data class UnexpectedCompositionOwner(val module: ModuleId) : ArchitecturePolicyFailure

    data class InvalidRuntimeCompositionDependencies(
        val missing: Set<ModuleId>,
        val unexpected: Set<ModuleId>,
    ) : ArchitecturePolicyFailure

    data class ModuleDependencyCycle(val members: Set<ModuleId>) : ArchitecturePolicyFailure
}
