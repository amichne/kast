package support.architecture

internal object KastPlatformModules {
    val all: List<ModulePolicy> = listOf(
        active(ModuleId.ANALYSIS_API, ModuleRole.LEGACY_HOST),
        active(
            ModuleId.ANALYSIS_SERVER,
            ModuleRole.LEGACY_HOST,
            ModuleId.ANALYSIS_API,
            ModuleId.INDEX_STORE,
        ),
        active(ModuleId.INDEX_STORE, ModuleRole.LEGACY_HOST, ModuleId.ANALYSIS_API),
        active(
            ModuleId.INDEXER,
            ModuleRole.LEGACY_HOST,
            ModuleId.ANALYSIS_API,
            ModuleId.ANALYSIS_SERVER,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.CHANGE_JOURNAL_SQLITE,
            ModuleId.CHANGE_PLAN_INTELLIJ,
            ModuleId.CHANGE_PLAN_SERVICE,
            ModuleId.CHANGE_PLAN_SPI,
            ModuleId.CHANGE_APPLY_INTELLIJ,
            ModuleId.CHANGE_APPLY_SERVICE,
            ModuleId.CHANGE_APPLY_SPI,
            ModuleId.CHANGE_RECOVERY_CONTRACT,
            ModuleId.CHANGE_RECOVERY_FILESYSTEM,
            ModuleId.CHANGE_RECOVERY_SERVICE,
            ModuleId.CHANGE_RECOVERY_SPI,
            ModuleId.CHANGE_VERIFY_INTELLIJ,
            ModuleId.CHANGE_VERIFY_SERVICE,
            ModuleId.CHANGE_VERIFY_SPI,
            ModuleId.EVIDENCE_SQLITE,
            ModuleId.EVIDENCE_SPI,
            ModuleId.INDEX_STORE,
            ModuleId.SYMBOL_CONTRACT,
            ModuleId.SYMBOL_INTELLIJ,
            ModuleId.WORKSPACE_INTELLIJ,
            ModuleId.WORKSPACE_SERVICE,
            ModuleId.WORKSPACE_SPI,
        ),
        active(ModuleId.KERNEL, ModuleRole.KERNEL),
        active(ModuleId.PROTOCOL_CONTRACT, ModuleRole.CONTRACT, ModuleId.KERNEL),
        active(
            ModuleId.PROTOCOL_REGISTRY,
            ModuleRole.CONTRACT,
            ModuleId.KERNEL,
            ModuleId.PROTOCOL_CONTRACT,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.SYMBOL_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.PROTOCOL_WIRE,
            ModuleRole.CONTRACT,
            ModuleId.KERNEL,
            ModuleId.PROTOCOL_CONTRACT,
            ModuleId.PROTOCOL_REGISTRY,
        ),
        active(ModuleId.WORKSPACE_CONTRACT, ModuleRole.CONTRACT, ModuleId.KERNEL),
        active(ModuleId.WORKSPACE_SPI, ModuleRole.SPI, ModuleId.WORKSPACE_CONTRACT),
        active(
            ModuleId.EVIDENCE_CONTRACT,
            ModuleRole.CONTRACT,
            ModuleId.KERNEL,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.EVIDENCE_SPI,
            ModuleRole.SPI,
            ModuleId.EVIDENCE_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.EVIDENCE_SQLITE,
            ModuleRole.SQLITE_ADAPTER,
            ModuleId.EVIDENCE_CONTRACT,
            ModuleId.EVIDENCE_SPI,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.WORKSPACE_SERVICE,
            ModuleRole.SERVICE,
            ModuleId.EVIDENCE_CONTRACT,
            ModuleId.EVIDENCE_SPI,
            ModuleId.WORKSPACE_CONTRACT,
            ModuleId.WORKSPACE_SPI,
        ),
        active(
            ModuleId.WORKSPACE_INTELLIJ,
            ModuleRole.WORKSPACE_ADAPTER,
            ModuleId.WORKSPACE_CONTRACT,
            ModuleId.WORKSPACE_SPI,
        ),
        active(
            ModuleId.SYMBOL_CONTRACT,
            ModuleRole.CONTRACT,
            ModuleId.KERNEL,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.SYMBOL_INTELLIJ,
            ModuleRole.INTELLIJ_READ_ADAPTER,
            ModuleId.SYMBOL_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
            ModuleId.WORKSPACE_SPI,
        ),
        active(
            ModuleId.PROTOCOL_CONTINUATION,
            ModuleRole.SERVICE,
            ModuleId.KERNEL,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.CHANGE_CONTRACT,
            ModuleRole.CONTRACT,
            ModuleId.KERNEL,
        ),
        active(
            ModuleId.CHANGE_PLAN_SPI,
            ModuleRole.SPI,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.CHANGE_PLAN_INTELLIJ,
            ModuleRole.INTELLIJ_READ_ADAPTER,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_PLAN_SPI,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleRole.CONTRACT,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_RECOVERY_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.CHANGE_JOURNAL_SQLITE,
            ModuleRole.SQLITE_ADAPTER,
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.CHANGE_VERIFY_SPI,
        ),
        active(
            ModuleId.CHANGE_PLAN_SERVICE,
            ModuleRole.SERVICE,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_PLAN_SPI,
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        planned(
            ModuleId.WORKSPACE_MUTATION_CONTRACT,
            ModuleRole.CONTRACT,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        planned(
            ModuleId.WORKSPACE_MUTATION_SERVICE,
            ModuleRole.SERVICE,
            ModuleId.WORKSPACE_MUTATION_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
            ModuleId.WORKSPACE_SPI,
        ),
        active(
            ModuleId.CHANGE_APPLY_SPI,
            ModuleRole.SPI,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.CHANGE_RECOVERY_CONTRACT,
        ),
        active(ModuleId.CHANGE_RECOVERY_CONTRACT, ModuleRole.CONTRACT, ModuleId.CHANGE_CONTRACT),
        active(
            ModuleId.CHANGE_RECOVERY_SPI,
            ModuleRole.SPI,
            ModuleId.CHANGE_RECOVERY_CONTRACT,
        ),
        active(
            ModuleId.CHANGE_RECOVERY_FILESYSTEM,
            ModuleRole.FILESYSTEM_WRITE_ADAPTER,
            ModuleId.CHANGE_RECOVERY_CONTRACT,
            ModuleId.CHANGE_RECOVERY_SPI,
        ),
        active(
            ModuleId.CHANGE_RECOVERY_SERVICE,
            ModuleRole.SERVICE,
            ModuleId.CHANGE_RECOVERY_CONTRACT,
            ModuleId.CHANGE_RECOVERY_SPI,
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.CHANGE_APPLY_SERVICE,
            ModuleRole.SERVICE,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_APPLY_SPI,
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.CHANGE_RECOVERY_CONTRACT,
        ),
        active(
            ModuleId.CHANGE_APPLY_INTELLIJ,
            ModuleRole.INTELLIJ_WRITE_ADAPTER,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_APPLY_SPI,
        ),
        planned(
            ModuleId.CHANGE_APPLY_FILESYSTEM,
            ModuleRole.FILESYSTEM_WRITE_ADAPTER,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_APPLY_SPI,
        ),
        active(
            ModuleId.CHANGE_VERIFY_SPI,
            ModuleRole.SPI,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.CHANGE_VERIFY_INTELLIJ,
            ModuleRole.INTELLIJ_READ_ADAPTER,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_VERIFY_SPI,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.CHANGE_VERIFY_SERVICE,
            ModuleRole.SERVICE,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.CHANGE_VERIFY_SPI,
            ModuleId.CHANGE_RECOVERY_CONTRACT,
            ModuleId.CHANGE_JOURNAL_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
            ModuleId.WORKSPACE_SPI,
        ),
        planned(
            ModuleId.RUNTIME_BINDINGS,
            ModuleRole.CONTRACT,
            ModuleId.CHANGE_CONTRACT,
            ModuleId.KERNEL,
            ModuleId.SYMBOL_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT,
        ),
        active(
            ModuleId.RUNTIME_SERVER,
            ModuleRole.TRANSPORT,
            ModuleId.PROTOCOL_CONTRACT,
            ModuleId.PROTOCOL_WIRE,
        ),
        active(
            ModuleId.CLI,
            ModuleRole.CLI,
            ModuleId.KERNEL,
            ModuleId.PROTOCOL_CONTRACT,
            ModuleId.PROTOCOL_REGISTRY,
            ModuleId.PROTOCOL_WIRE,
        ),
        planned(
            ModuleId.RUNTIME_COMPOSITION,
            ModuleRole.COMPOSITION,
            *ModuleId.entries.filterNot {
                it in setOf(
                    ModuleId.ANALYSIS_API,
                    ModuleId.ANALYSIS_SERVER,
                    ModuleId.INDEX_STORE,
                    ModuleId.CLI,
                    ModuleId.INDEXER,
                    ModuleId.RUNTIME_COMPOSITION,
                ) + cleanSlateOnlyModuleIds()
            }.toTypedArray(),
        ),
    )

    private fun active(
        id: ModuleId,
        role: ModuleRole,
        vararg allowedDependencies: ModuleId,
    ): ModulePolicy = module(id, ModuleLifecycle.ACTIVE, role, *allowedDependencies)

    private fun planned(
        id: ModuleId,
        role: ModuleRole,
        vararg allowedDependencies: ModuleId,
    ): ModulePolicy = module(id, ModuleLifecycle.PLANNED, role, *allowedDependencies)

    private fun module(
        id: ModuleId,
        lifecycle: ModuleLifecycle,
        role: ModuleRole,
        vararg allowedDependencies: ModuleId,
    ): ModulePolicy = ModulePolicy(
        id = id,
        lifecycle = lifecycle,
        role = role,
        allowedProjectDependencies = allowedDependencies.toSet(),
        allowedEffects = allowedEffects(id),
    )

    private fun allowedEffects(id: ModuleId): Set<ForbiddenEffect> = when (id) {
        ModuleId.ANALYSIS_API,
        ModuleId.ANALYSIS_SERVER,
            -> setOf(ForbiddenEffect.ANALYSIS_BACKEND, ForbiddenEffect.FILESYSTEM_WRITE)
        ModuleId.INDEX_STORE -> setOf(ForbiddenEffect.JDBC, ForbiddenEffect.FILESYSTEM_WRITE)
        ModuleId.INDEXER -> setOf(ForbiddenEffect.FILESYSTEM_WRITE)
        ModuleId.WORKSPACE_INTELLIJ -> setOf(
            ForbiddenEffect.INTELLIJ_PLATFORM,
            ForbiddenEffect.GRADLE_PLATFORM,
            ForbiddenEffect.GRADLE_IMPORT,
        )
        ModuleId.CHANGE_JOURNAL_SQLITE,
        ModuleId.EVIDENCE_SQLITE,
            -> setOf(ForbiddenEffect.JDBC)
        ModuleId.SYMBOL_INTELLIJ,
        ModuleId.CHANGE_PLAN_INTELLIJ,
        ModuleId.CHANGE_VERIFY_INTELLIJ,
            -> setOf(ForbiddenEffect.INTELLIJ_PLATFORM)
        ModuleId.CHANGE_APPLY_INTELLIJ -> setOf(
            ForbiddenEffect.INTELLIJ_PLATFORM,
            ForbiddenEffect.INTELLIJ_WRITE,
        )
        ModuleId.CHANGE_APPLY_FILESYSTEM,
        ModuleId.CHANGE_RECOVERY_FILESYSTEM,
            -> setOf(ForbiddenEffect.FILESYSTEM_WRITE, ForbiddenEffect.SOURCE_FILESYSTEM_WRITE)
        ModuleId.RUNTIME_COMPOSITION,
        ModuleId.RUNTIME_SERVER,
            -> emptySet()
        ModuleId.CLI -> setOf(ForbiddenEffect.PROCESS_CONTROL)
        else -> emptySet()
    }

    private fun cleanSlateOnlyModuleIds(): Set<ModuleId> = setOf(
        ModuleId.SYMBOL_SERVICE,
        ModuleId.RELATION_CONTRACT,
        ModuleId.RELATION_SERVICE,
        ModuleId.RELATION_INTELLIJ,
        ModuleId.TRAVERSAL_CONTRACT,
        ModuleId.TRAVERSAL_SERVICE,
        ModuleId.DIAGNOSTIC_CONTRACT,
        ModuleId.DIAGNOSTIC_SERVICE,
        ModuleId.DIAGNOSTIC_INTELLIJ,
        ModuleId.CHANGE_PLAN,
        ModuleId.CHANGE_APPLY,
        ModuleId.CHANGE_VERIFY,
        ModuleId.CHANGE_RECOVERY,
        ModuleId.CHANGE_INTELLIJ,
    )
}
