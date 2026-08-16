package support.architecture

/** Materialized KCS-013 diagnostic policies kept separate to preserve the policy file limit. */
internal object KastDiagnosticModules {
    val all: List<ModulePolicy> = listOf(
        module(
            ModuleId.DIAGNOSTIC_CONTRACT,
            ModuleRole.CONTRACT,
            ModuleId.KERNEL,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        module(
            ModuleId.DIAGNOSTIC_SERVICE,
            ModuleRole.SERVICE,
            ModuleId.DIAGNOSTIC_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        module(
            ModuleId.DIAGNOSTIC_INTELLIJ,
            ModuleRole.INTELLIJ_READ_ADAPTER,
            ModuleId.DIAGNOSTIC_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
            effects = setOf(ForbiddenEffect.INTELLIJ_PLATFORM),
        ),
    )

    private fun module(
        id: ModuleId,
        role: ModuleRole,
        vararg dependencies: ModuleId,
        effects: Set<ForbiddenEffect> = emptySet(),
    ): ModulePolicy = ModulePolicy(
        id = id,
        lifecycle = ModuleLifecycle.ACTIVE,
        role = role,
        allowedProjectDependencies = dependencies.toSet(),
        allowedEffects = effects,
    )
}
