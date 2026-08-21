package support.architecture

enum class ModuleLifecycle {
    ACTIVE,
    PLANNED,
    RETIRED,
}

enum class ModuleRole {
    LEGACY_HOST,
    KERNEL,
    CONTRACT,
    SPI,
    SERVICE,
    INTELLIJ_READ_ADAPTER,
    INTELLIJ_WRITE_ADAPTER,
    FILESYSTEM_WRITE_ADAPTER,
    SQLITE_ADAPTER,
    WORKSPACE_ADAPTER,
    COMPOSITION,
    TRANSPORT,
    CLI,
    INDEXER_HOST,
}

enum class ForbiddenEffect {
    INTELLIJ_PLATFORM,
    INTELLIJ_WRITE,
    FILESYSTEM_WRITE,
    SOURCE_FILESYSTEM_WRITE,
    JDBC,
    GRADLE_PLATFORM,
    GRADLE_IMPORT,
    RECURSIVE_VFS_REFRESH,
    WORKSPACE_TRANSITION,
    GRAPH_BUILD,
    PROCESS_CONTROL,
    ANALYSIS_BACKEND,
}

enum class ModuleId(val projectPath: String) {
    KERNEL(":kernel"),
    DISTRIBUTION_CONTRACT(":distribution:contract"),
    DISTRIBUTION_MANAGED(":distribution:managed"),
    PROTOCOL_CONTRACT(":protocol:contract"),
    PROTOCOL_REGISTRY(":protocol:registry"),
    PROTOCOL_WIRE(":protocol:wire"),
    WORKSPACE_CONTRACT(":workspace:contract"),
    WORKSPACE_SERVICE(":workspace:service"),
    WORKSPACE_INTELLIJ(":workspace:intellij"),
    SYMBOL_CONTRACT(":symbol:contract"),
    SYMBOL_SERVICE(":symbol:service"),
    SYMBOL_INTELLIJ(":symbol:intellij"),
    RELATION_CONTRACT(":relation:contract"),
    RELATION_SERVICE(":relation:service"),
    RELATION_INTELLIJ(":relation:intellij"),
    TRAVERSAL_CONTRACT(":traversal:contract"),
    TRAVERSAL_SERVICE(":traversal:service"),
    DIAGNOSTIC_CONTRACT(":diagnostic:contract"),
    DIAGNOSTIC_SERVICE(":diagnostic:service"),
    DIAGNOSTIC_INTELLIJ(":diagnostic:intellij"),
    CHANGE_CONTRACT(":change:contract"),
    CHANGE_PLAN(":change:plan"),
    CHANGE_APPLY(":change:apply"),
    CHANGE_VERIFY(":change:verify"),
    CHANGE_RECOVERY(":change:recovery"),
    CHANGE_INTELLIJ(":change:intellij"),
    EVIDENCE_CONTRACT(":evidence:contract"),
    EVIDENCE_SQLITE(":evidence:sqlite"),
    RUNTIME_SERVER(":runtime:server"),
    RUNTIME_COMPOSITION(":runtime:composition"),
    CLI(":cli"),
    INDEXER(":indexer"),
}

data class ModulePolicy(
    val id: ModuleId,
    val lifecycle: ModuleLifecycle,
    val role: ModuleRole,
    val allowedProjectDependencies: Set<ModuleId>,
    val allowedEffects: Set<ForbiddenEffect>,
)

@JvmInline
value class JvmClassName(val internalName: String)

@JvmInline
value class JvmMemberName(val value: String)

@JvmInline
value class JvmDescriptor(val value: String)

data class JvmMember(
    val owner: JvmClassName,
    val name: JvmMemberName,
    val descriptor: JvmDescriptor,
) {
    companion object {
        fun of(owner: String, name: String, descriptor: String): JvmMember =
            JvmMember(JvmClassName(owner), JvmMemberName(name), JvmDescriptor(descriptor))
    }
}

data class EffectObservation(
    val module: ModuleId,
    val effect: ForbiddenEffect,
    val caller: JvmMember,
    val target: JvmMember,
)

data class ProjectDependencyObservation(
    val consumer: ModuleId,
    val dependency: ModuleId,
)

data class ArchitecturePolicyDefinition(
    val modules: List<ModulePolicy>,
)

sealed interface ArchitecturePolicyValidation {
    data class Valid(val architecture: ValidatedArchitecturePolicy) : ArchitecturePolicyValidation

    data class Invalid(val failures: List<ArchitecturePolicyFailure>) : ArchitecturePolicyValidation
}

class ValidatedArchitecturePolicy internal constructor(
    val modules: Map<ModuleId, ValidatedModulePolicy>,
    val moduleOrder: List<ModuleId>,
)
