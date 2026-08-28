package support.architecture

enum class ModuleCost {
    HOST_NEUTRAL,
    BOUNDED_READ,
    PHYSICAL_EFFECT,
    RUNTIME_ORCHESTRATION,
    LEGACY,
}

enum class ModuleRoleConvention(
    val role: ModuleRole,
    val pluginId: String,
) {
    KERNEL(ModuleRole.KERNEL, "kast.role.kernel"),
    CONTRACT(ModuleRole.CONTRACT, "kast.role.contract"),
    SPI(ModuleRole.SPI, "kast.role.spi"),
    SERVICE(ModuleRole.SERVICE, "kast.role.service"),
    IDE_READ_ONLY(ModuleRole.IDE_READ_ONLY, "kast.role.ide-read-only"),
    IDE_HOST(ModuleRole.IDE_HOST, "kast.role.ide-host"),
    INTELLIJ_READ(ModuleRole.INTELLIJ_READ_ADAPTER, "kast.role.intellij-read"),
    INTELLIJ_WRITE(ModuleRole.INTELLIJ_WRITE_ADAPTER, "kast.role.intellij-write"),
    FILESYSTEM_WRITE(ModuleRole.FILESYSTEM_WRITE_ADAPTER, "kast.role.filesystem-write"),
    SQLITE(ModuleRole.SQLITE_ADAPTER, "kast.role.sqlite"),
    WORKSPACE(ModuleRole.WORKSPACE_ADAPTER, "kast.role.workspace"),
    TRANSPORT(ModuleRole.TRANSPORT, "kast.role.transport"),
    COMPOSITION(ModuleRole.COMPOSITION, "kast.role.composition"),
    CLI(ModuleRole.CLI, "kast.role.cli"),
    INDEXER_HOST(ModuleRole.INDEXER_HOST, "kast.role.indexer-host"),
}

sealed interface ModuleRoleConventionRequirement {
    data object UnmarkedLegacy : ModuleRoleConventionRequirement

    data class Required(
        val convention: ModuleRoleConvention,
    ) : ModuleRoleConventionRequirement
}

internal data class ModuleRoleBoundary(
    val role: ModuleRole,
    val cost: ModuleCost,
    val conventionRequirement: ModuleRoleConventionRequirement,
    val allowedDependencyRoles: Set<ModuleRole>,
    val allowedDependencyCosts: Set<ModuleCost>,
    val allowedExportedDependencyRoles: Set<ModuleRole>,
    val allowedEffects: Set<ForbiddenEffect>,
)

class ValidatedModulePolicy internal constructor(
    private val policy: ModulePolicy,
    internal val boundary: ModuleRoleBoundary,
) {
    val id: ModuleId get() = policy.id
    val lifecycle: ModuleLifecycle get() = policy.lifecycle
    val role: ModuleRole get() = boundary.role
    val cost: ModuleCost get() = boundary.cost
    val conventionRequirement: ModuleRoleConventionRequirement get() = boundary.conventionRequirement
    val allowedProjectDependencies: Set<ModuleId> get() = policy.allowedProjectDependencies
    val allowedEffects: Set<ForbiddenEffect> get() = policy.allowedEffects
}

sealed interface ModulePolicyValidation {
    data class Valid(val module: ValidatedModulePolicy) : ModulePolicyValidation

    data class Invalid(val failures: List<ArchitecturePolicyFailure>) : ModulePolicyValidation
}

internal object ModulePolicyValidator {
    /**
     * Proof transition: `(ModulePolicy, declared ModulePolicy graph) -> ValidatedModulePolicy`.
     *
     * Establishes that the module's direct dependencies, registry direction, independent dependency
     * costs, and allowed effects remain within its declared role boundary or the closed KCS-017 and
     * KCS-018 collapsed-module edge set.
     * [ModulePolicyValidation.Invalid] is the closed expected failure. Raw module policy
     * construction is permitted only in the canonical architecture definition and policy tests.
     */
    fun validate(
        module: ModulePolicy,
        modules: Map<ModuleId, ModulePolicy>,
    ): ModulePolicyValidation {
        val boundary = ModuleRoleBoundaries.forRole(module.role)
        val failures = buildList {
            module.allowedProjectDependencies.forEach { dependencyId ->
                val dependency = modules[dependencyId] ?: return@forEach
                val dependencyBoundary = ModuleRoleBoundaries.forRole(dependency.role)
                val observation = ProjectDependencyObservation(module.id, dependency.id)
                if (
                    dependency.role !in boundary.allowedDependencyRoles &&
                    observation !in KastCleanSlateCrossRoleDependencies.all
                ) {
                    add(
                        ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                            module.id,
                            dependency.id,
                            dependency.role,
                        ),
                    )
                }
                if (dependencyBoundary.cost !in boundary.allowedDependencyCosts) {
                    add(
                        ArchitecturePolicyFailure.ForbiddenModuleCostDependency(
                            module.id,
                            dependency.id,
                            dependencyBoundary.cost,
                        ),
                    )
                }
                if (
                    module.role == ModuleRole.CONTRACT &&
                    module.id !in setOf(ModuleId.PROTOCOL_REGISTRY, ModuleId.PROTOCOL_WIRE) &&
                    dependencyId == ModuleId.PROTOCOL_REGISTRY
                ) {
                    add(ArchitecturePolicyFailure.FeatureContractDependsOnRegistry(module.id))
                }
            }
            module.allowedEffects
                .filterNot(boundary.allowedEffects::contains)
                .forEach { effect ->
                    add(ArchitecturePolicyFailure.ForbiddenModuleRoleEffect(module.id, effect))
                }
        }
        return if (failures.isEmpty()) {
            ModulePolicyValidation.Valid(ValidatedModulePolicy(module, boundary))
        } else {
            ModulePolicyValidation.Invalid(failures)
        }
    }
}

internal object KastCleanSlateCrossRoleDependencies {
    val all: Set<ProjectDependencyObservation> = setOf(
        ProjectDependencyObservation(ModuleId.CHANGE_APPLY, ModuleId.CHANGE_RECOVERY),
        ProjectDependencyObservation(ModuleId.CHANGE_VERIFY, ModuleId.CHANGE_APPLY),
        ProjectDependencyObservation(ModuleId.CHANGE_INTELLIJ, ModuleId.CHANGE_APPLY),
        ProjectDependencyObservation(ModuleId.CHANGE_INTELLIJ, ModuleId.CHANGE_RECOVERY),
        ProjectDependencyObservation(ModuleId.CHANGE_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
        ProjectDependencyObservation(ModuleId.EVIDENCE_SQLITE, ModuleId.CHANGE_APPLY),
        ProjectDependencyObservation(ModuleId.EVIDENCE_SQLITE, ModuleId.CHANGE_VERIFY),
        ProjectDependencyObservation(ModuleId.RELATION_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
        ProjectDependencyObservation(ModuleId.TOPOLOGY_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
        ProjectDependencyObservation(ModuleId.DIAGNOSTIC_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
    )
}

private object ModuleRoleBoundaries {
    fun forRole(role: ModuleRole): ModuleRoleBoundary = when (role) {
        ModuleRole.LEGACY_HOST -> ModuleRoleBoundary(
            role = role,
            cost = ModuleCost.LEGACY,
            conventionRequirement = ModuleRoleConventionRequirement.UnmarkedLegacy,
            allowedDependencyRoles = ModuleRole.entries.toSet(),
            allowedDependencyCosts = ModuleCost.entries.toSet(),
            allowedExportedDependencyRoles = ModuleRole.entries.toSet(),
            allowedEffects = ForbiddenEffect.entries.toSet(),
        )
        ModuleRole.KERNEL -> boundary(
            role,
            ModuleCost.HOST_NEUTRAL,
            ModuleRoleConvention.KERNEL,
            emptySet(),
            emptySet(),
            emptySet(),
        )
        ModuleRole.CONTRACT -> boundary(
            role,
            ModuleCost.HOST_NEUTRAL,
            ModuleRoleConvention.CONTRACT,
            setOf(ModuleRole.KERNEL, ModuleRole.CONTRACT),
            safeReadCosts,
            setOf(ModuleRole.KERNEL, ModuleRole.CONTRACT),
        )
        ModuleRole.SPI -> boundary(
            role,
            ModuleCost.HOST_NEUTRAL,
            ModuleRoleConvention.SPI,
            inwardRoles,
            safeReadCosts,
            inwardRoles,
        )
        ModuleRole.SERVICE -> boundary(
            role,
            ModuleCost.HOST_NEUTRAL,
            ModuleRoleConvention.SERVICE,
            inwardRoles,
            safeReadCosts,
            allowedEffects = setOf(
                ForbiddenEffect.WORKSPACE_TRANSITION,
                ForbiddenEffect.TOPOLOGY_BUILD_AUTHORITY,
            ),
        )
        ModuleRole.IDE_READ_ONLY -> boundary(
            role,
            ModuleCost.BOUNDED_READ,
            ModuleRoleConvention.IDE_READ_ONLY,
            inwardRoles + ModuleRole.IDE_READ_ONLY + ModuleRole.INTELLIJ_READ_ADAPTER,
            safeReadCosts,
            allowedEffects = setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
            ),
        )
        ModuleRole.IDE_HOST -> boundary(
            role,
            ModuleCost.RUNTIME_ORCHESTRATION,
            ModuleRoleConvention.IDE_HOST,
            setOf(
                ModuleRole.CONTRACT,
                ModuleRole.IDE_READ_ONLY,
                ModuleRole.INTELLIJ_READ_ADAPTER,
                ModuleRole.INTELLIJ_WRITE_ADAPTER,
                ModuleRole.COMPOSITION,
            ),
            setOf(
                ModuleCost.HOST_NEUTRAL,
                ModuleCost.BOUNDED_READ,
                ModuleCost.PHYSICAL_EFFECT,
                ModuleCost.RUNTIME_ORCHESTRATION,
            ),
            allowedEffects = setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.UDS_BIND,
                ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE,
            ),
        )
        ModuleRole.INTELLIJ_READ_ADAPTER -> boundary(
            role,
            ModuleCost.BOUNDED_READ,
            ModuleRoleConvention.INTELLIJ_READ,
            inwardRoles,
            safeReadCosts,
            allowedEffects = setOf(ForbiddenEffect.INTELLIJ_PLATFORM),
        )
        ModuleRole.INTELLIJ_WRITE_ADAPTER -> boundary(
            role,
            ModuleCost.PHYSICAL_EFFECT,
            ModuleRoleConvention.INTELLIJ_WRITE,
            inwardRoles,
            safeReadCosts,
            allowedEffects = setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.INTELLIJ_WRITE,
                ForbiddenEffect.FILESYSTEM_WRITE,
                ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
            ),
        )
        ModuleRole.FILESYSTEM_WRITE_ADAPTER -> boundary(
            role,
            ModuleCost.PHYSICAL_EFFECT,
            ModuleRoleConvention.FILESYSTEM_WRITE,
            inwardRoles,
            safeReadCosts,
            allowedEffects = setOf(
                ForbiddenEffect.FILESYSTEM_WRITE,
                ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
            ),
        )
        ModuleRole.SQLITE_ADAPTER -> boundary(
            role,
            ModuleCost.PHYSICAL_EFFECT,
            ModuleRoleConvention.SQLITE,
            inwardRoles,
            safeReadCosts,
            allowedEffects = setOf(
                ForbiddenEffect.JDBC,
                ForbiddenEffect.FILESYSTEM_WRITE,
                ForbiddenEffect.TOPOLOGY_PUBLICATION,
            ),
        )
        ModuleRole.WORKSPACE_ADAPTER -> boundary(
            role,
            ModuleCost.PHYSICAL_EFFECT,
            ModuleRoleConvention.WORKSPACE,
            inwardRoles,
            safeReadCosts,
            allowedEffects = setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.GRADLE_PLATFORM,
                ForbiddenEffect.GRADLE_IMPORT,
                ForbiddenEffect.GRAPH_BUILD,
            ),
        )
        ModuleRole.TRANSPORT -> boundary(
            role,
            ModuleCost.RUNTIME_ORCHESTRATION,
            ModuleRoleConvention.TRANSPORT,
            inwardRoles,
            safeReadCosts,
            allowedExportedDependencyRoles = inwardRoles,
            allowedEffects = emptySet(),
        )
        ModuleRole.COMPOSITION -> boundary(
            role = role,
            cost = ModuleCost.RUNTIME_ORCHESTRATION,
            convention = ModuleRoleConvention.COMPOSITION,
            allowedDependencyRoles = ModuleRole.entries.toSet() - ModuleRole.LEGACY_HOST,
            allowedDependencyCosts = ModuleCost.entries.toSet() - ModuleCost.LEGACY,
            allowedEffects = emptySet(),
        )
        ModuleRole.CLI -> boundary(
            role,
            ModuleCost.RUNTIME_ORCHESTRATION,
            ModuleRoleConvention.CLI,
            setOf(ModuleRole.KERNEL, ModuleRole.CONTRACT, ModuleRole.FILESYSTEM_WRITE_ADAPTER),
            setOf(ModuleCost.HOST_NEUTRAL, ModuleCost.PHYSICAL_EFFECT),
            allowedEffects = setOf(ForbiddenEffect.PROCESS_CONTROL),
        )
        ModuleRole.INDEXER_HOST -> boundary(
            role,
            ModuleCost.RUNTIME_ORCHESTRATION,
            ModuleRoleConvention.INDEXER_HOST,
            setOf(ModuleRole.COMPOSITION),
            setOf(ModuleCost.RUNTIME_ORCHESTRATION),
            allowedEffects = setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.FILESYSTEM_WRITE,
            ),
        )
    }

    private fun boundary(
        role: ModuleRole,
        cost: ModuleCost,
        convention: ModuleRoleConvention,
        allowedDependencyRoles: Set<ModuleRole>,
        allowedDependencyCosts: Set<ModuleCost>,
        allowedExportedDependencyRoles: Set<ModuleRole> = emptySet(),
        allowedEffects: Set<ForbiddenEffect> = emptySet(),
    ): ModuleRoleBoundary = ModuleRoleBoundary(
        role,
        cost,
        ModuleRoleConventionRequirement.Required(convention),
        allowedDependencyRoles,
        allowedDependencyCosts,
        allowedExportedDependencyRoles,
        allowedEffects,
    )

    private val inwardRoles = setOf(ModuleRole.KERNEL, ModuleRole.CONTRACT, ModuleRole.SPI)
    private val safeReadCosts = setOf(ModuleCost.HOST_NEUTRAL, ModuleCost.BOUNDED_READ)
}
