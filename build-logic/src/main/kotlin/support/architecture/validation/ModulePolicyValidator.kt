package support.architecture

import java.util.Collections

enum class ModuleCost {
    HOST_NEUTRAL,
    BOUNDED_READ,
    PHYSICAL_EFFECT,
    RUNTIME_ORCHESTRATION,
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
    APP_SERVER(ModuleRole.APP_SERVER, "kast.role.app-server"),
    CLI(ModuleRole.CLI, "kast.role.cli"),
    INDEXER_HOST(ModuleRole.INDEXER_HOST, "kast.role.indexer-host"),
}

internal data class ModuleRoleBoundary(
    val role: ModuleRole,
    val cost: ModuleCost,
    val requiredConvention: ModuleRoleConvention,
    val allowedDependencyRoles: Set<ModuleRole>,
    val allowedDependencyCosts: Set<ModuleCost>,
    val allowedExportedDependencyRoles: Set<ModuleRole>,
    val allowedEffects: Set<ForbiddenEffect>,
    val allowedScopedEffects: Set<ForbiddenEffect>,
)

class ValidatedModulePolicy internal constructor(
    policy: ModulePolicy,
    boundary: ModuleRoleBoundary,
) {
    internal val boundary: ModuleRoleBoundary = boundary.copy(
        allowedDependencyRoles = immutableSet(boundary.allowedDependencyRoles),
        allowedDependencyCosts = immutableSet(boundary.allowedDependencyCosts),
        allowedExportedDependencyRoles = immutableSet(boundary.allowedExportedDependencyRoles),
        allowedEffects = immutableSet(boundary.allowedEffects),
        allowedScopedEffects = immutableSet(boundary.allowedScopedEffects),
    )

    private val policy: ModulePolicy = policy.copy(
        allowedProjectDependencies = immutableSet(policy.allowedProjectDependencies),
        allowedEffects = immutableSet(policy.allowedEffects),
        allowedScopedEffectCallers = Collections.unmodifiableMap(
            policy.allowedScopedEffectCallers.mapValuesTo(linkedMapOf()) { (_, callers) ->
                immutableSet(callers)
            },
        ),
    )

    val id: ModuleId get() = policy.id
    val lifecycle: ModuleLifecycle get() = policy.lifecycle
    val role: ModuleRole get() = boundary.role
    val cost: ModuleCost get() = boundary.cost
    val requiredConvention: ModuleRoleConvention get() = boundary.requiredConvention
    val allowedProjectDependencies: Set<ModuleId> get() = policy.allowedProjectDependencies
    val allowedEffects: Set<ForbiddenEffect> get() = policy.allowedEffects
    val allowedScopedEffectCallers: Map<ForbiddenEffect, Set<JvmClassName>>
        get() = policy.allowedScopedEffectCallers
}

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

sealed interface ModulePolicyValidation {
    data class Valid(val module: ValidatedModulePolicy) : ModulePolicyValidation

    data class Invalid(val failures: List<ArchitecturePolicyFailure>) : ModulePolicyValidation
}

internal object ModulePolicyValidator {
    /**
     * Proof transition: `(ModulePolicy, declared ModulePolicy graph) -> ValidatedModulePolicy`.
     *
     * Establishes that the module's direct dependencies, registry direction, independent dependency
     * costs, and allowed effects remain within its declared role boundary or the closed named
     * collapsed-module and shared-read adapter edge set.
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
            module.allowedScopedEffectCallers.forEach { (effect, callers) ->
                if (effect !in boundary.allowedScopedEffects) {
                    add(ArchitecturePolicyFailure.ForbiddenModuleRoleEffect(module.id, effect))
                }
                if (callers.isEmpty()) {
                    add(ArchitecturePolicyFailure.EmptyScopedEffectCallerSet(module.id, effect))
                }
                if (effect in module.allowedEffects) {
                    add(ArchitecturePolicyFailure.RedundantScopedEffectAllowance(module.id, effect))
                }
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
        ProjectDependencyObservation(ModuleId.CHANGE_VERIFY, ModuleId.CHANGE_RECOVERY),
        ProjectDependencyObservation(ModuleId.RUNTIME_HOSTED, ModuleId.EVIDENCE_SQLITE),
        ProjectDependencyObservation(ModuleId.CHANGE_INTELLIJ, ModuleId.CHANGE_APPLY),
        ProjectDependencyObservation(ModuleId.CHANGE_INTELLIJ, ModuleId.CHANGE_RECOVERY),
        ProjectDependencyObservation(ModuleId.CHANGE_INTELLIJ, ModuleId.CHANGE_VERIFY),
        ProjectDependencyObservation(ModuleId.CHANGE_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
        ProjectDependencyObservation(ModuleId.WORKSPACE_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
        ProjectDependencyObservation(ModuleId.EVIDENCE_SQLITE, ModuleId.CHANGE_APPLY),
        ProjectDependencyObservation(ModuleId.EVIDENCE_SQLITE, ModuleId.CHANGE_VERIFY),
        ProjectDependencyObservation(ModuleId.SYMBOL_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
        ProjectDependencyObservation(ModuleId.SOURCE_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
        ProjectDependencyObservation(ModuleId.RELATION_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
        ProjectDependencyObservation(ModuleId.TOPOLOGY_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
        ProjectDependencyObservation(ModuleId.DIAGNOSTIC_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ),
    )
}

private object ModuleRoleBoundaries {
    fun forRole(role: ModuleRole): ModuleRoleBoundary = when (role) {
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
                ForbiddenEffect.PROJECT_FILE_INDEX_AUTHORITY,
                ForbiddenEffect.PROJECT_READ_EPOCH_AUTHORITY,
            ),
        )
        ModuleRole.IDE_HOST -> boundary(
            role,
            ModuleCost.RUNTIME_ORCHESTRATION,
            ModuleRoleConvention.IDE_HOST,
            setOf(
                ModuleRole.KERNEL,
                ModuleRole.CONTRACT,
                ModuleRole.SERVICE,
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
                ForbiddenEffect.PROJECT_READ_EPOCH_AUTHORITY,
                ForbiddenEffect.UDS_BIND,
                ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE,
            ),
            allowedScopedEffects = setOf(ForbiddenEffect.FILESYSTEM_WRITE, ForbiddenEffect.PHYSICAL_SOURCE_READ, ForbiddenEffect.SOURCE_CONTENT_HASH),
        )
        ModuleRole.INTELLIJ_READ_ADAPTER -> boundary(
            role,
            ModuleCost.BOUNDED_READ,
            ModuleRoleConvention.INTELLIJ_READ,
            inwardRoles,
            safeReadCosts,
            allowedEffects = setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.TOPOLOGY_SOURCE_ROOT_VFS_SYNCHRONIZATION,
            ),
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
            inwardRoles + ModuleRole.FILESYSTEM_WRITE_ADAPTER,
            safeReadCosts + ModuleCost.PHYSICAL_EFFECT,
            allowedEffects = setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.GRADLE_PLATFORM,
                ForbiddenEffect.GRADLE_IMPORT,
                ForbiddenEffect.GRAPH_BUILD,
            ),
            allowedScopedEffects = setOf(ForbiddenEffect.FILESYSTEM_WRITE),
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
            allowedDependencyRoles = ModuleRole.entries.toSet(),
            allowedDependencyCosts = ModuleCost.entries.toSet(),
            allowedEffects = emptySet(),
        )
        ModuleRole.APP_SERVER -> boundary(
            role,
            ModuleCost.RUNTIME_ORCHESTRATION,
            ModuleRoleConvention.APP_SERVER,
            setOf(ModuleRole.KERNEL, ModuleRole.CONTRACT, ModuleRole.FILESYSTEM_WRITE_ADAPTER),
            setOf(ModuleCost.HOST_NEUTRAL, ModuleCost.PHYSICAL_EFFECT),
            allowedEffects = setOf(ForbiddenEffect.PROCESS_CONTROL),
            allowedScopedEffects = setOf(ForbiddenEffect.FILESYSTEM_WRITE),
        )
        ModuleRole.CLI -> boundary(
            role,
            ModuleCost.RUNTIME_ORCHESTRATION,
            ModuleRoleConvention.CLI,
            setOf(ModuleRole.APP_SERVER, ModuleRole.KERNEL, ModuleRole.CONTRACT, ModuleRole.FILESYSTEM_WRITE_ADAPTER),
            setOf(ModuleCost.RUNTIME_ORCHESTRATION, ModuleCost.HOST_NEUTRAL, ModuleCost.PHYSICAL_EFFECT),
            allowedEffects = setOf(ForbiddenEffect.PROCESS_CONTROL),
            allowedScopedEffects = setOf(ForbiddenEffect.FILESYSTEM_WRITE),
        )
        ModuleRole.INDEXER_HOST -> boundary(
            role,
            ModuleCost.RUNTIME_ORCHESTRATION,
            ModuleRoleConvention.INDEXER_HOST,
            setOf(ModuleRole.COMPOSITION, ModuleRole.CONTRACT, ModuleRole.FILESYSTEM_WRITE_ADAPTER),
            setOf(ModuleCost.RUNTIME_ORCHESTRATION, ModuleCost.HOST_NEUTRAL, ModuleCost.PHYSICAL_EFFECT),
            allowedEffects = setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.FILESYSTEM_WRITE,
                ForbiddenEffect.UDS_BIND,
                ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE,
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
        allowedScopedEffects: Set<ForbiddenEffect> = emptySet(),
    ): ModuleRoleBoundary = ModuleRoleBoundary(
        role,
        cost,
        convention,
        allowedDependencyRoles,
        allowedDependencyCosts,
        allowedExportedDependencyRoles,
        allowedEffects,
        allowedScopedEffects,
    )

    private val inwardRoles = setOf(ModuleRole.KERNEL, ModuleRole.CONTRACT, ModuleRole.SPI)
    private val safeReadCosts = setOf(ModuleCost.HOST_NEUTRAL, ModuleCost.BOUNDED_READ)
}
