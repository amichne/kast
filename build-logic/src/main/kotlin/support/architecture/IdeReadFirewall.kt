package support.architecture

internal enum class IdeReadForbiddenAuthority(
    val requiredEffect: ForbiddenEffect,
    val target: JvmMember,
) {
    PROJECT_OPEN(
        ForbiddenEffect.PROJECT_OPEN,
        JvmMember.of("com/intellij/openapi/project/ex/ProjectManagerEx", "openProject", "()V"),
    ),
    GRADLE_IMPORT(
        ForbiddenEffect.GRADLE_IMPORT,
        JvmMember.of(
            "com/intellij/openapi/externalSystem/util/ExternalSystemUtil",
            "refreshProject",
            "()V",
        ),
    ),
    VFS_REFRESH(
        ForbiddenEffect.RECURSIVE_VFS_REFRESH,
        JvmMember.of("com/intellij/openapi/vfs/VfsUtil", "markDirtyAndRefresh", "()V"),
    ),
    INTELLIJ_WRITE(
        ForbiddenEffect.INTELLIJ_WRITE,
        JvmMember.of("com/intellij/openapi/command/WriteCommandAction", "runWriteCommandAction", "()V"),
    ),
    CHANGE(
        ForbiddenEffect.MUTATION_AUTHORITY,
        JvmMember.of("io/github/amichne/kast/change/apply/ChangeApplicationPort", "apply", "()V"),
    ),
    TOPOLOGY(
        ForbiddenEffect.TOPOLOGY_AUTHORITY,
        JvmMember.of("io/github/amichne/kast/topology/build/TopologyBuildAuthority", "build", "()V"),
    ),
    JDBC(
        ForbiddenEffect.JDBC,
        JvmMember.of("java/sql/Connection", "prepareStatement", "()V"),
    ),
    PROCESS_CONTROL(
        ForbiddenEffect.PROCESS_CONTROL,
        JvmMember.of("java/lang/ProcessBuilder", "start", "()V"),
    ),
    ISOLATED_RUNTIME(
        ForbiddenEffect.ISOLATED_RUNTIME,
        JvmMember.of("io/github/amichne/kast/indexer/KastIndexerHost", "run", "()V"),
    ),
}

internal sealed interface IdeReadFirewallFailure {
    data class MissingModule(val module: ModuleId) : IdeReadFirewallFailure
    data class LifecycleMismatch(
        val module: ModuleId,
        val observed: ModuleLifecycle,
    ) : IdeReadFirewallFailure
    data class LifecycleProgressionMismatch(
        val observedActiveModules: Set<ModuleId>,
    ) : IdeReadFirewallFailure
    data class RoleMismatch(val module: ModuleId, val observed: ModuleRole) : IdeReadFirewallFailure
    data class AllowedEffectsMismatch(
        val module: ModuleId,
        val observed: Set<ForbiddenEffect>,
    ) : IdeReadFirewallFailure
    data class AuthorityClassificationMissing(
        val authority: IdeReadForbiddenAuthority,
        val required: ForbiddenEffect,
    ) : IdeReadFirewallFailure
    data class AuthorityAllowed(
        val module: ModuleId,
        val authority: IdeReadForbiddenAuthority,
        val effect: ForbiddenEffect,
    ) : IdeReadFirewallFailure
}

internal enum class IdeReadFirewallStage(val activeModules: Set<ModuleId>) {
    DECLARED(emptySet()),
    PLUGIN_SPLIT(setOf(ModuleId.IDE_PLUGIN)),
    WORKSPACE_SPLIT(setOf(ModuleId.IDE_PLUGIN, ModuleId.WORKSPACE_INTELLIJ_READ)),
    RUNTIME_SPLIT(
        setOf(ModuleId.IDE_PLUGIN, ModuleId.WORKSPACE_INTELLIJ_READ, ModuleId.RUNTIME_IDE_READ),
    ),
}

internal class IdeReadFirewallProof internal constructor(
    val stage: IdeReadFirewallStage,
    val modules: List<ValidatedModulePolicy>,
    val forbiddenAuthorities: Map<IdeReadForbiddenAuthority, Set<ForbiddenEffect>>,
)

internal sealed interface IdeReadFirewallResult {
    data class Complete(val proof: IdeReadFirewallProof) : IdeReadFirewallResult
    data class Rejected(val failures: List<IdeReadFirewallFailure>) : IdeReadFirewallResult
}

internal object IdeReadFirewall {
    val moduleIds: Set<ModuleId> = setOf(
        ModuleId.WORKSPACE_INTELLIJ_READ,
        ModuleId.RUNTIME_IDE_READ,
        ModuleId.IDE_PLUGIN,
    )

    /**
     * Proof transition: `ValidatedArchitecturePolicy -> IdeReadFirewallResult`.
     * Establishes one valid monotonic IDE-read materialization stage, exact role/effect ownership,
     * and finite rejection of every project-open, repair, mutation, topology, JDBC, process, and
     * isolated-runtime reference.
     * Expected policy gaps are closed [IdeReadFirewallFailure]; JVM primitives are extracted only
     * by [JvmEffectScanner] or the fixed build-policy fixture boundary.
     */
    fun derive(policy: ValidatedArchitecturePolicy): IdeReadFirewallResult {
        val failures = mutableListOf<IdeReadFirewallFailure>()
        val modules = mutableListOf<ValidatedModulePolicy>()
        moduleIds.forEach { moduleId ->
            if (moduleId !in policy.modules) {
                failures += IdeReadFirewallFailure.MissingModule(moduleId)
            } else {
                modules += policy.modules.getValue(moduleId)
            }
        }
        modules.forEach { module ->
            if (module.lifecycle !in setOf(ModuleLifecycle.PLANNED, ModuleLifecycle.ACTIVE)) {
                failures += IdeReadFirewallFailure.LifecycleMismatch(module.id, module.lifecycle)
            }
            if (module.role != ModuleRole.IDE_READ_ONLY) {
                failures += IdeReadFirewallFailure.RoleMismatch(module.id, module.role)
            }
            val expectedEffects = when (module.id) {
                ModuleId.IDE_PLUGIN -> setOf(
                    ForbiddenEffect.INTELLIJ_PLATFORM,
                    ForbiddenEffect.UDS_BIND,
                    ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE,
                )
                ModuleId.RUNTIME_IDE_READ,
                ModuleId.WORKSPACE_INTELLIJ_READ,
                -> setOf(ForbiddenEffect.INTELLIJ_PLATFORM)
                else -> emptySet()
            }
            if (module.allowedEffects != expectedEffects) {
                failures += IdeReadFirewallFailure.AllowedEffectsMismatch(
                    module.id,
                    module.allowedEffects,
                )
            }
        }
        if (modules.size != moduleIds.size) {
            return IdeReadFirewallResult.Rejected(failures)
        }
        val observedActive = modules.filter { it.lifecycle == ModuleLifecycle.ACTIVE }
            .mapTo(linkedSetOf()) { it.id }
        val stages = IdeReadFirewallStage.entries.filter { it.activeModules == observedActive }
        if (stages.size != 1) {
            failures += IdeReadFirewallFailure.LifecycleProgressionMismatch(observedActive)
        }
        val classifier = modules.first()
        val caller = JvmMember.of("io/github/amichne/kast/ide/HostedReadFixture", "read", "()V")
        val classifications = IdeReadForbiddenAuthority.entries.associateWith { authority ->
            JvmEffectClassifier.classify(classifier, caller, authority.target)
        }
        classifications.forEach { (authority, effects) ->
            if (authority.requiredEffect !in effects) {
                failures += IdeReadFirewallFailure.AuthorityClassificationMissing(
                    authority,
                    authority.requiredEffect,
                )
            }
            modules.filter { authority.requiredEffect in it.allowedEffects }.forEach { module ->
                failures += IdeReadFirewallFailure.AuthorityAllowed(
                    module.id,
                    authority,
                    authority.requiredEffect,
                )
            }
        }
        return if (failures.isEmpty()) {
            IdeReadFirewallResult.Complete(
                IdeReadFirewallProof(stages.single(), modules, classifications),
            )
        } else {
            IdeReadFirewallResult.Rejected(failures)
        }
    }
}
