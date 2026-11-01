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
    private val bundleSubmit = JvmMember.of("io/github/amichne/kast/runtime/hosted/lifecycle/NativeHostBundleRefreshRoot", "submit",
        "(Lkotlin/jvm/functions/Function0;)Lio/github/amichne/kast/runtime/hosted/lifecycle/HostRefreshSubmission;")
    private val bundleFiles = JvmMember.of("com/intellij/openapi/vfs/LocalFileSystem", "refreshFiles",
        "(Ljava/lang/Iterable;ZZLjava/lang/Runnable;)V")

    @Test
    fun `Host bundle refresh admits only the exact submit method and native signatures`() {
        for (target in listOf(dirty, bundleFiles)) {
            assertInstanceOf<ArchitectureAdmission.Accepted>(admitRefresh(ModuleId.RUNTIME_HOSTED, bundleSubmit, target))
            assertInstanceOf<ArchitectureAdmission.Rejected>(admitRefresh(ModuleId.RUNTIME_HOSTED, bundleSubmit.copy(name = JvmMemberName("other")), target))
            assertInstanceOf<ArchitectureAdmission.Rejected>(admitRefresh(ModuleId.RUNTIME_HOSTED, bundleSubmit.copy(descriptor = JvmDescriptor("()V")), target))
            assertInstanceOf<ArchitectureAdmission.Rejected>(admitRefresh(ModuleId.RUNTIME_HOSTED, bundleSubmit, target.copy(descriptor = JvmDescriptor("()V"))))
        }
    }

    @Test
    fun `Host bundle authority grants no workspace import or other refresh entry points`() {
        for ((target, effect) in listOf(refresh to ForbiddenEffect.GRADLE_IMPORT, files to ForbiddenEffect.RECURSIVE_VFS_REFRESH,
            bundleFiles.copy(descriptor = JvmDescriptor("(Ljava/lang/Iterable;)V")) to ForbiddenEffect.RECURSIVE_VFS_REFRESH,
            JvmMember.of("com/intellij/openapi/vfs/VirtualFile", "refresh", "(ZZ)V") to ForbiddenEffect.RECURSIVE_VFS_REFRESH,
        )) assertInstanceOf<ArchitectureAdmission.Rejected>(admit(ModuleId.RUNTIME_HOSTED, bundleSubmit, target, setOf(effect)))
    }

    @Test
    fun `bundle refresh signatures remain forbidden to general runtime and read-only code`() {
        for (target in listOf(dirty, bundleFiles)) {
            assertInstanceOf<ArchitectureAdmission.Rejected>(admitRefresh(ModuleId.RUNTIME_HOSTED, JvmMember.of("io/github/amichne/kast/runtime/hosted/HostedSemanticServices", "effect", "()V"), target))
            assertInstanceOf<ArchitectureAdmission.Rejected>(admitRefresh(ModuleId.WORKSPACE_INTELLIJ_READ, bundleSubmit, target))
            assertInstanceOf<ArchitectureAdmission.Rejected>(admitRefresh(ModuleId.RUNTIME_HOSTED, JvmMember.of(refreshOwner, "effect", "()V"), bundleFiles))
        }
    }

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
    fun `import callback admits only the platform future overload in the existing owner`() {
        val builder = "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder"
        val future = JvmMember.of(builder, "withCallback", "(Ljava/util/concurrent/CompletableFuture;)L$builder;")
        val legacy = JvmMember.of(builder, "withCallback", "(Lcom/intellij/openapi/externalSystem/service/project/ExternalProjectRefreshCallback;)L$builder;")
        assertInstanceOf<ArchitectureAdmission.Accepted>(admit(ModuleId.RUNTIME_HOSTED, refreshOwner + "Kt", future))
        assertInstanceOf<ArchitectureAdmission.Rejected>(admit(ModuleId.RUNTIME_HOSTED, refreshOwner + "Kt", legacy))
        assertInstanceOf<ArchitectureAdmission.Rejected>(admit(ModuleId.RUNTIME_HOSTED,
            "io/github/amichne/kast/runtime/hosted/HostedSemanticServices", future))
    }

    @Test
    fun `initial link remains confined to the existing refresh owner`() {
        val link = JvmMember.of("com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "linkExternalProject", "(Lcom/intellij/openapi/externalSystem/settings/ExternalProjectSettings;Lcom/intellij/openapi/externalSystem/importing/ImportSpecBuilder;)V")
        assertInstanceOf<ArchitectureAdmission.Accepted>(admit(ModuleId.RUNTIME_HOSTED, refreshOwner, link))
        assertInstanceOf<ArchitectureAdmission.Rejected>(admit(ModuleId.WORKSPACE_INTELLIJ_READ, "io/github/amichne/kast/workspace/intellij/read/PassiveRead", link))
        assertInstanceOf<ArchitectureAdmission.Rejected>(admit(ModuleId.RUNTIME_HOSTED, "io/github/amichne/kast/runtime/hosted/HostedSemanticServices", link))
    }

    @Test
    fun `refresh permission cannot open reconfigure rebuild or refresh all projects`() {
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
        return admit(module, JvmMember.of(owner, "effect", "()V"), target)
    }

    private fun admitRefresh(module: ModuleId, caller: JvmMember, target: JvmMember): ArchitectureAdmission =
        admit(module, caller, target, setOf(ForbiddenEffect.RECURSIVE_VFS_REFRESH))

    private fun admit(module: ModuleId, caller: JvmMember, target: JvmMember, required: Set<ForbiddenEffect> = emptySet()): ArchitectureAdmission {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(KastArchitecturePolicy.validate()).architecture
        val policy = architecture.modules.getValue(module)
        val effects = EffectRules.classify(policy.role, caller, target)
        assertTrue(effects.isNotEmpty())
        assertTrue(effects.containsAll(required), "missing classified effect: $required for $target")
        return ArchitectureAdmission.evaluate(architecture, ObservedArchitecture(
            modules = architecture.modules.values.filter { it.lifecycle == ModuleLifecycle.ACTIVE }.mapTo(linkedSetOf(), ValidatedModulePolicy::id),
            projectDependencies = emptySet(),
            effects = effects.mapTo(linkedSetOf()) { EffectObservation(module, it, caller, target) },
        ))
    }
}
