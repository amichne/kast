package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/** Synthetic bytes exercise the production scanner and admission rule without loading IntelliJ. */
class WorkspaceRefreshAuthorityDependencyTest {
    private val owner = "io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPort"
    private val files = JvmMember.of(
        "com/intellij/openapi/vfs/newvfs/RefreshQueue", "refresh",
        "(ZZLjava/lang/Runnable;[Lcom/intellij/openapi/vfs/VirtualFile;)V",
    )
    private val model = JvmMember.of(
        "com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProject",
        "(Ljava/lang/String;Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;)V",
    )

    @Test
    fun `pure workspace decisions reject native observation imports and adapter dependencies`() {
        val architecture = canonical()
        val native = JvmMember.of("com/intellij/openapi/project/Project", "isDisposed", "()Z")
        val effects = scan(architecture, ModuleId.WORKSPACE_CONTRACT, "fixture/WorkspaceDecision", native)
        assertEquals(setOf(ForbiddenEffect.INTELLIJ_PLATFORM), effects.mapTo(linkedSetOf(), EffectObservation::effect))
        val dependency = ProjectDependencyObservation(ModuleId.WORKSPACE_CONTRACT, ModuleId.WORKSPACE_INTELLIJ_READ)
        val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(
            admit(architecture, effects, setOf(dependency)),
        )
        assertTrue(ArchitectureViolation.UnapprovedProjectDependency(dependency) in rejected.violations)
        assertTrue(effects.all { ArchitectureViolation.ForbiddenEffectUse(it) in rejected.violations })
    }

    @Test
    fun `native refresh entry points remain visible and rejected outside their single owner`() {
        val architecture = canonical()
        val callers = mapOf(
            ModuleId.RUNTIME_HOSTED to "io/github/amichne/kast/runtime/hosted/HostedSemanticServices",
            ModuleId.APP_SERVER to "io/github/amichne/kast/appserver/WorkspaceBroker",
            ModuleId.DISTRIBUTION_CLI to "io/github/amichne/kast/distribution/cli/Installer",
            ModuleId.CLI to "io/github/amichne/kast/cli/WorkspaceClient",
            ModuleId.CHANGE_INTELLIJ to "io/github/amichne/kast/change/intellij/CompetingWorkspaceRefresh",
        )
        for ((module, caller) in callers) {
            for ((target, effect) in mapOf(files to ForbiddenEffect.RECURSIVE_VFS_REFRESH, model to ForbiddenEffect.GRADLE_IMPORT)) {
                val effects = scan(architecture, module, caller, target)
                val refresh = effects.single { it.effect == effect }
                val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(admit(architecture, effects))
                assertTrue(ArchitectureViolation.ForbiddenEffectUse(refresh) in rejected.violations, "$module: $target")
            }
        }
    }

    @Test
    fun `exact native owner and descriptors are accepted while extra entry points are rejected`() {
        val architecture = canonical()
        assertInstanceOf<ArchitectureAdmission.Accepted>(
            admit(architecture, scan(architecture, ModuleId.RUNTIME_HOSTED, owner, files, model)),
        )
        for (target in listOf(files.copy(descriptor = JvmDescriptor("()V")), model.copy(name = JvmMemberName("refreshProjects")))) {
            assertInstanceOf<ArchitectureAdmission.Rejected>(
                admit(architecture, scan(architecture, ModuleId.RUNTIME_HOSTED, owner, target)),
            )
        }
    }

    @Test
    fun `adding a second scoped caller cannot weaken native lifecycle ownership`() {
        val secondOwner = JvmClassName("io/github/amichne/kast/runtime/hosted/workspace/SecondRefreshPort")
        val definition = KastArchitecturePolicy.definition()
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            ArchitecturePolicyValidator.validate(definition.copy(modules = definition.modules.map { module ->
                if (module.id != ModuleId.RUNTIME_HOSTED) module
                else module.copy(allowedScopedEffectCallers = module.allowedScopedEffectCallers.mapValues { (effect, callers) ->
                    if (effect in setOf(ForbiddenEffect.GRADLE_IMPORT, ForbiddenEffect.RECURSIVE_VFS_REFRESH)) callers + secondOwner
                    else callers
                })
            })),
        ).architecture
        for (target in listOf(files, model)) {
            val effects = scan(architecture, ModuleId.RUNTIME_HOSTED, secondOwner.internalName, target)
            val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(admit(architecture, effects))
            assertTrue(rejected.violations.any { violation ->
                violation is ArchitectureViolation.ForbiddenEffectUse &&
                    violation.observation.target == target &&
                    violation.observation.effect in setOf(ForbiddenEffect.GRADLE_IMPORT, ForbiddenEffect.RECURSIVE_VFS_REFRESH)
            })
        }
    }

    @Test
    fun `source mutation adapters retain their separate native file effect boundary`() {
        val architecture = canonical()
        val mutationRefresh = JvmMember.of(
            "com/intellij/openapi/vfs/VfsUtil", "markDirtyAndRefresh",
            "(ZZZ[Lcom/intellij/openapi/vfs/VirtualFile;)V",
        )
        val effects = scan(architecture, ModuleId.CHANGE_INTELLIJ,
            "io/github/amichne/kast/change/intellij/IntellijExistingSourceRollback", mutationRefresh)
        assertEquals(setOf(ForbiddenEffect.INTELLIJ_PLATFORM), effects.mapTo(linkedSetOf(), EffectObservation::effect))
        assertInstanceOf<ArchitectureAdmission.Accepted>(admit(architecture, effects))
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(KastArchitecturePolicy.validate()).architecture

    private fun admit(
        architecture: ValidatedArchitecturePolicy,
        effects: Set<EffectObservation>,
        dependencies: Set<ProjectDependencyObservation> = emptySet(),
    ): ArchitectureAdmission = ArchitectureAdmission.evaluate(architecture, ObservedArchitecture(
        modules = architecture.modules.values.filter { it.lifecycle == ModuleLifecycle.ACTIVE }
            .mapTo(linkedSetOf(), ValidatedModulePolicy::id),
        projectDependencies = dependencies,
        effects = effects,
    ))

    private fun scan(
        architecture: ValidatedArchitecturePolicy,
        module: ModuleId,
        caller: String,
        vararg targets: JvmMember,
    ): Set<EffectObservation> {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, caller, null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "effect", "()V", null, null).apply {
                visitCode()
                targets.forEach { target ->
                    visitMethodInsn(Opcodes.INVOKESTATIC, target.owner.internalName, target.name.value,
                        target.descriptor.value, false)
                }
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        return assertInstanceOf<BytecodeScanOutcome.Scanned>(JvmEffectScanner.scanBytes(
            architecture.modules.getValue(module), listOf(HostedReadClassBytes.capture("$caller.class", bytes)),
        )).effects()
    }
}
