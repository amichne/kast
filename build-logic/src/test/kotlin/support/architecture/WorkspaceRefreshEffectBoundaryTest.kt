package support.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class WorkspaceRefreshEffectBoundaryTest {
    private val refreshOwner = "io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPort"
    private val refresh = JvmMember.of("com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProject",
        "(Ljava/lang/String;Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;)V")
    private val files = JvmMember.of("com/intellij/openapi/vfs/newvfs/RefreshQueue", "refresh",
        "(ZZLjava/lang/Runnable;[Lcom/intellij/openapi/vfs/VirtualFile;)V")

    private val dirty = JvmMember.of("com/intellij/openapi/vfs/VfsUtil", "markDirty",
        "(ZZ[Lcom/intellij/openapi/vfs/VirtualFile;)Ljava/util/List;")

    @Test
    fun `only explicit adapter calls may reload one linked build or refresh files`() {
        assertInstanceOf<ArchitectureAdmission.Accepted>(admit(ModuleId.RUNTIME_HOSTED, refreshOwner, refresh))
        assertInstanceOf<ArchitectureAdmission.Accepted>(admit(ModuleId.RUNTIME_HOSTED, refreshOwner, files))
        for (target in listOf(refresh, files, dirty)) {
            assertInstanceOf<ArchitectureAdmission.Accepted>(admit(ModuleId.RUNTIME_HOSTED, refreshOwner, target))
            assertInstanceOf<ArchitectureAdmission.Rejected>(admit(ModuleId.RUNTIME_HOSTED,
                "io/github/amichne/kast/runtime/hosted/HostedSemanticServices", target))
            assertInstanceOf<ArchitectureAdmission.Rejected>(admit(ModuleId.WORKSPACE_INTELLIJ_READ,
                "io/github/amichne/kast/workspace/intellij/read/PassiveRead", target))
        }
    }

    @Test
    fun `refresh permission cannot open link reconfigure rebuild or refresh all projects`() {
        for (target in listOf(
            refresh.copy(name = JvmMemberName("linkExternalProject")),
            refresh.copy(name = JvmMemberName("refreshProjects")),
            refresh.copy(name = JvmMemberName("refreshProjectForNewlyOpenedProject")),
            JvmMember.of("org/jetbrains/plugins/gradle/settings/GradleProjectSettings", "<init>", "()V"),
            JvmMember.of("com/intellij/openapi/project/ex/ProjectManagerEx", "openProject", "()V"),
            JvmMember.of("com/intellij/util/indexing/FileBasedIndex", "requestRebuild", "()V"),
        )) assertInstanceOf<ArchitectureAdmission.Rejected>(admit(ModuleId.RUNTIME_HOSTED, refreshOwner, target))
    }

    private fun admit(module: ModuleId, owner: String, target: JvmMember): ArchitectureAdmission {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(KastArchitecturePolicy.validate()).architecture
        val policy = architecture.modules.getValue(module)
        val caller = JvmMember.of(owner, "effect", "()V")
        val effects = EffectRules.classify(policy.role, caller, target)
        assertTrue(effects.isNotEmpty())
        return ArchitectureAdmission.evaluate(architecture, ObservedArchitecture(
            modules = architecture.modules.values.filter { it.lifecycle == ModuleLifecycle.ACTIVE }.mapTo(linkedSetOf(), ValidatedModulePolicy::id),
            projectDependencies = emptySet(),
            effects = effects.mapTo(linkedSetOf()) { EffectObservation(module, it, caller, target) },
        ))
    }
}
