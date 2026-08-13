package support.architecture

import support.architecture.process.MutationRuntimeProcessId
import support.architecture.process.MutationRuntimeProcessPolicy

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
}

enum class ForbiddenEffect {
    INTELLIJ_WRITE,
    FILESYSTEM_WRITE,
    SOURCE_FILESYSTEM_WRITE,
    JDBC,
    GRADLE_IMPORT,
    GRAPH_BUILD,
    PROCESS_CONTROL,
    ANALYSIS_BACKEND,
}

enum class ModuleId(val projectPath: String) {
    ANALYSIS_API(":analysis-api"),
    ANALYSIS_SERVER(":analysis-server"),
    INDEX_STORE(":index-store"),
    INDEXER(":indexer"),
    KERNEL(":kernel"),
    PROTOCOL_REGISTRY(":protocol:registry"),
    WORKSPACE_CONTRACT(":workspace:contract"),
    WORKSPACE_SPI(":workspace:spi"),
    WORKSPACE_SERVICE(":workspace:service"),
    WORKSPACE_INTELLIJ(":workspace:intellij"),
    EVIDENCE_CONTRACT(":evidence:contract"),
    EVIDENCE_SPI(":evidence:spi"),
    SYMBOL_CONTRACT(":symbol:contract"),
    SYMBOL_INTELLIJ(":symbol:intellij"),
    PROTOCOL_CONTINUATION(":protocol:continuation"),
    CHANGE_CONTRACT(":change:contract"),
    CHANGE_PLAN_SPI(":change:plan:spi"),
    CHANGE_PLAN_INTELLIJ(":change:plan:intellij"),
    CHANGE_PLAN_SERVICE(":change:plan:service"),
    CHANGE_JOURNAL_CONTRACT(":change:journal:contract"),
    CHANGE_JOURNAL_SQLITE(":change:journal:sqlite"),
    WORKSPACE_MUTATION_CONTRACT(":workspace:mutation:contract"),
    WORKSPACE_MUTATION_SERVICE(":workspace:mutation:service"),
    CHANGE_APPLY_SPI(":change:apply:spi"),
    CHANGE_APPLY_SERVICE(":change:apply:service"),
    CHANGE_APPLY_INTELLIJ(":change:apply:intellij"),
    CHANGE_APPLY_FILESYSTEM(":change:apply:filesystem"),
    CHANGE_RECOVERY_CONTRACT(":change:recovery:contract"),
    CHANGE_RECOVERY_FILESYSTEM(":change:recovery:filesystem"),
    CHANGE_RECOVERY_SERVICE(":change:recovery:service"),
    CHANGE_VERIFY_SPI(":change:verify:spi"),
    CHANGE_VERIFY_INTELLIJ(":change:verify:intellij"),
    CHANGE_VERIFY_SERVICE(":change:verify:service"),
    EVIDENCE_SQLITE(":evidence:sqlite"),
    RUNTIME_BINDINGS(":runtime:bindings"),
    RUNTIME_COMPOSITION(":runtime:composition"),
    RUNTIME_SERVER(":runtime:server"),
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
        fun of(
            owner: String,
            name: String,
            descriptor: String,
        ): JvmMember = JvmMember(
            JvmClassName(owner),
            JvmMemberName(name),
            JvmDescriptor(descriptor),
        )
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

sealed interface LegacyViolationKey {
    data class UnapprovedProjectDependency(
        val dependency: ProjectDependencyObservation,
    ) : LegacyViolationKey

    data class ForbiddenEffectUse(val observation: EffectObservation) : LegacyViolationKey
}

data class LegacyAllowance(
    val violation: LegacyViolationKey,
    val retirementTask: MutationDeliveryTaskId,
)

enum class LegacyMigrationLifecycle {
    PLANNED,
    ACTIVE,
    COMPLETED,
}

data class LegacyMigrationEdgePolicy(
    val dependency: ProjectDependencyObservation,
    val lifecycle: LegacyMigrationLifecycle,
    val retirementTask: MutationDeliveryTaskId,
)

sealed interface ValidatedLegacyMigrationEdge {
    val dependency: ProjectDependencyObservation
    val retirementTask: MutationDeliveryTaskId

    class Planned internal constructor(
        override val dependency: ProjectDependencyObservation,
        override val retirementTask: MutationDeliveryTaskId,
    ) : ValidatedLegacyMigrationEdge

    class Active internal constructor(
        override val dependency: ProjectDependencyObservation,
        override val retirementTask: MutationDeliveryTaskId,
    ) : ValidatedLegacyMigrationEdge
}

enum class MutationDeliveryPhase {
    FOUNDATION,
    PLANNING,
    APPLY,
    TRANSITION_AND_VERIFICATION,
    RECOVERY,
    MIGRATION,
    PROOF,
}

enum class MutationDeliveryTaskId {
    F01, F02, F03, F04,
    P01, P02, P03, P04, P05,
    A01, A02, A03, A04, A05, A06, A07, A08,
    V01, V02, V03, V04, V05,
    R01, R02, R03,
    M01, M02, M03,
    T01, T02, T03, T04, T05,
}

enum class MutationDeliveryTaskLifecycle {
    OPEN,
    COMPLETED,
}

sealed interface MutationDeliveryOwner {
    data object BuildLogic : MutationDeliveryOwner

    data object EndToEndCorpus : MutationDeliveryOwner

    data class Modules(val ids: Set<ModuleId>) : MutationDeliveryOwner
}

data class MutationDeliveryTaskPolicy(
    val id: MutationDeliveryTaskId,
    val phase: MutationDeliveryPhase,
    val name: String,
    val lifecycle: MutationDeliveryTaskLifecycle,
    val dependsOn: Set<MutationDeliveryTaskId>,
    val owner: MutationDeliveryOwner,
)

data class ArchitecturePolicyDefinition(
    val modules: List<ModulePolicy>,
    val mutationDeliveryTasks: List<MutationDeliveryTaskPolicy>,
    val mutationRuntimeProcesses: List<MutationRuntimeProcessPolicy>,
    val legacyAllowances: List<LegacyAllowance> = emptyList(),
    val legacyMigrationEdges: List<LegacyMigrationEdgePolicy> = emptyList(),
)

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
}

sealed interface ArchitecturePolicyValidation {
    data class Valid(val architecture: ValidatedArchitecturePolicy) : ArchitecturePolicyValidation

    data class Invalid(val failures: List<ArchitecturePolicyFailure>) : ArchitecturePolicyValidation
}

class ValidatedArchitecturePolicy internal constructor(
    val modules: Map<ModuleId, ValidatedModulePolicy>,
    val mutationDeliveryTasks: Map<MutationDeliveryTaskId, MutationDeliveryTaskPolicy>,
    val mutationRuntimeProcesses: Map<MutationRuntimeProcessId, MutationRuntimeProcessPolicy>,
    val moduleOrder: List<ModuleId>,
    val mutationDeliveryOrder: List<MutationDeliveryTaskId>,
    val mutationRuntimeProcessOrder: List<MutationRuntimeProcessId>,
    val legacyAllowances: Set<LegacyAllowance>,
    val legacyMigrationEdges: Map<ProjectDependencyObservation, ValidatedLegacyMigrationEdge>,
)
