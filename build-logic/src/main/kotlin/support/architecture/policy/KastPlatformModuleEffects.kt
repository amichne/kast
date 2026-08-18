package support.architecture

internal object KastPlatformModuleEffects {
    fun forModule(id: ModuleId): Set<ForbiddenEffect> = when (id) {
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
        ModuleId.RELATION_INTELLIJ,
        ModuleId.CHANGE_PLAN_INTELLIJ,
        ModuleId.CHANGE_VERIFY_INTELLIJ,
            -> setOf(ForbiddenEffect.INTELLIJ_PLATFORM)
        ModuleId.CHANGE_APPLY_INTELLIJ,
        ModuleId.CHANGE_INTELLIJ,
            -> setOf(
            ForbiddenEffect.INTELLIJ_PLATFORM,
            ForbiddenEffect.INTELLIJ_WRITE,
        )
        ModuleId.CHANGE_APPLY_FILESYSTEM,
        ModuleId.CHANGE_RECOVERY_FILESYSTEM,
            -> setOf(ForbiddenEffect.FILESYSTEM_WRITE, ForbiddenEffect.SOURCE_FILESYSTEM_WRITE)
        ModuleId.DISTRIBUTION_MANAGED -> setOf(ForbiddenEffect.FILESYSTEM_WRITE)
        ModuleId.RUNTIME_COMPOSITION,
        ModuleId.RUNTIME_SERVER,
            -> emptySet()
        ModuleId.CLI -> setOf(ForbiddenEffect.PROCESS_CONTROL)
        else -> emptySet()
    }
}
