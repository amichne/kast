package support.architecture

/** Architecture policy owned by the typed query family. */
internal object KastQueryModules {
    val all: List<ModulePolicy> = listOf(
        ModulePolicy(
            id = ModuleId.QUERY_CONTRACT,
            lifecycle = ModuleLifecycle.ACTIVE,
            role = ModuleRole.CONTRACT,
            allowedProjectDependencies = setOf(
                ModuleId.KERNEL,
                ModuleId.RELATION_CONTRACT,
                ModuleId.SOURCE_CONTRACT,
                ModuleId.SYMBOL_CONTRACT,
                ModuleId.WORKSPACE_CONTRACT,
            ),
            allowedEffects = emptySet(),
        ),
        ModulePolicy(
            id = ModuleId.QUERY_SERVICE,
            lifecycle = ModuleLifecycle.ACTIVE,
            role = ModuleRole.SERVICE,
            allowedProjectDependencies = setOf(
                ModuleId.KERNEL,
                ModuleId.QUERY_CONTRACT,
                ModuleId.RELATION_CONTRACT,
                ModuleId.SOURCE_CONTRACT,
                ModuleId.SYMBOL_CONTRACT,
            ),
            allowedEffects = emptySet(),
        ),
    )

    /**
     * Adds the query family and grants only runtime composition the corresponding construction
     * dependencies. No other existing module gains query authority implicitly.
     */
    fun integrate(base: List<ModulePolicy>): List<ModulePolicy> =
        base.map { module ->
            if (module.id == ModuleId.RUNTIME_COMPOSITION) {
                module.copy(
                    allowedProjectDependencies = module.allowedProjectDependencies +
                        setOf(ModuleId.QUERY_CONTRACT, ModuleId.QUERY_SERVICE),
                )
            } else {
                module
            }
        } + all
}
