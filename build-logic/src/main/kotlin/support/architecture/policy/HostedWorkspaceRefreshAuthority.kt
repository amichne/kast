package support.architecture

/** Each native effect stays within its exact lifecycle owner and entry points. */
internal object HostedWorkspaceRefreshAuthority {
    val owner = JvmClassName("io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPort")
    val specOwner = JvmClassName("io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPortKt")
    val bundleOwner = JvmClassName("io/github/amichne/kast/runtime/hosted/lifecycle/NativeHostBundleRefreshRoot")
    private val bundleSubmit = JvmMember.of(bundleOwner.internalName, "submit",
        "(Lkotlin/jvm/functions/Function0;)Lio/github/amichne/kast/runtime/hosted/lifecycle/HostRefreshSubmission;")
    private const val BUILDER = "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder"
    private val managedArguments = JvmMember.of(BUILDER, "withArguments", "(Ljava/lang/String;)L$BUILDER;")
    private val quietImport = JvmMember.of(specOwner.internalName, "quietWorkspaceImport",
        "(Lcom/intellij/openapi/project/Project;Ljava/util/concurrent/CompletableFuture;Z)L$BUILDER;")
    private val privateGradleSettings = JvmMember.of(owner.internalName, "privateGradleSettings",
        "(Lio/github/amichne/kast/runtime/hosted/lifecycle/ManagedHeadlessHost;Ljava/nio/file/Path;)Lio/github/amichne/kast/kernel/Refinement;")
    private val privateGradleTargets = setOf(
        JvmMember.of("org/jetbrains/plugins/gradle/service/project/open/GradleProjectImportUtil", "setupGradleSettings", "(Lorg/jetbrains/plugins/gradle/settings/GradleSettings;)V"),
        JvmMember.of("org/jetbrains/plugins/gradle/settings/GradleProjectSettings", "setGradleJvm", "(Ljava/lang/String;)V"),
    )
    private val importTargets = setOf(
        JvmMember.of(BUILDER, "<type>", ""),
        JvmMember.of(BUILDER, "<init>", "(Lcom/intellij/openapi/project/Project;Lcom/intellij/openapi/externalSystem/model/ProjectSystemId;)V"),
        JvmMember.of(BUILDER, "use", "(Lcom/intellij/openapi/externalSystem/service/execution/ProgressExecutionMode;)L$BUILDER;"),
        JvmMember.of(BUILDER, "withImportProjectData", "(Z)L$BUILDER;"),
        JvmMember.of(BUILDER, "withActivateToolWindowOnStart", "(Z)L$BUILDER;"),
        JvmMember.of(BUILDER, "withActivateToolWindowOnFailure", "(Z)L$BUILDER;"),
        JvmMember.of(BUILDER, "dontNavigateToError", "()L$BUILDER;"),
        JvmMember.of("com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "linkExternalProject", "(Lcom/intellij/openapi/externalSystem/settings/ExternalProjectSettings;L$BUILDER;)V"),
        JvmMember.of("org/jetbrains/plugins/gradle/service/project/open/GradleProjectImportUtil", "createLinkSettings", "(Ljava/nio/file/Path;Lcom/intellij/openapi/project/Project;)Lorg/jetbrains/plugins/gradle/settings/GradleProjectSettings;"),
        JvmMember.of(BUILDER, "withCallback", "(Ljava/util/concurrent/CompletableFuture;)L$BUILDER;"),
        JvmMember.of("com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProject", "(Ljava/lang/String;L$BUILDER;)V"),
    )
    private val dirtyTree = JvmMember.of("com/intellij/openapi/vfs/VfsUtil", "markDirty",
        "(ZZ[Lcom/intellij/openapi/vfs/VirtualFile;)Ljava/util/List;")
    private val fileTargets = setOf(JvmMember.of("com/intellij/openapi/vfs/newvfs/RefreshQueue", "refresh",
        "(ZZLjava/lang/Runnable;[Lcom/intellij/openapi/vfs/VirtualFile;)V"), dirtyTree,
    )
    private val bundleTargets = setOf(dirtyTree,
        JvmMember.of("com/intellij/openapi/vfs/LocalFileSystem", "refreshFiles",
            "(Ljava/lang/Iterable;ZZLjava/lang/Runnable;)V"),
    )

    /** Scoped caller grants do not authorize other entry points. */
    fun retainsBoundary(effect: EffectObservation): Boolean {
        return when (effect.effect) {
            ForbiddenEffect.GRADLE_IMPORT ->
                effect.module == ModuleId.RUNTIME_HOSTED &&
                    ((effect.caller.owner in setOf(owner, specOwner) && effect.target in importTargets) ||
                        (effect.caller == privateGradleSettings && effect.target in privateGradleTargets) ||
                        (effect.caller == quietImport && effect.target == managedArguments))
            ForbiddenEffect.RECURSIVE_VFS_REFRESH ->
                effect.module == ModuleId.RUNTIME_HOSTED &&
                    when {
                        effect.caller.owner == owner -> effect.target in fileTargets
                        effect.caller == bundleSubmit -> effect.target in bundleTargets
                        else -> false
                    }
            else -> true
        }
    }
}
