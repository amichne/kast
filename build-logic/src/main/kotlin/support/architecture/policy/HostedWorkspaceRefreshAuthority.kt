package support.architecture

/** Explicit lifecycle authority is confined to one adapter and the exact asynchronous native entry points. */
internal object HostedWorkspaceRefreshAuthority {
    val owner = JvmClassName("io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPort")
    val specOwner = JvmClassName("io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPortKt")
    private const val BUILDER = "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder"
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
        JvmMember.of(BUILDER, "withCallback", "(Lcom/intellij/openapi/externalSystem/service/project/ExternalProjectRefreshCallback;)L$BUILDER;"),
        JvmMember.of("com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProject", "(Ljava/lang/String;L$BUILDER;)V"),
    )
    private val fileTargets = setOf(JvmMember.of("com/intellij/openapi/vfs/newvfs/RefreshQueue", "refresh",
        "(ZZLjava/lang/Runnable;[Lcom/intellij/openapi/vfs/VirtualFile;)V"),
        JvmMember.of("com/intellij/openapi/vfs/VfsUtil", "markDirty",
            "(ZZ[Lcom/intellij/openapi/vfs/VirtualFile;)Ljava/util/List;"),
    )

    /** A scoped policy allowance cannot grant a second native lifecycle or widen its entry points. */
    fun retainsBoundary(effect: EffectObservation): Boolean {
        return when (effect.effect) {
            ForbiddenEffect.GRADLE_IMPORT ->
                effect.module == ModuleId.RUNTIME_HOSTED &&
                    effect.caller.owner in setOf(owner, specOwner) && effect.target in importTargets
            ForbiddenEffect.RECURSIVE_VFS_REFRESH ->
                effect.module == ModuleId.RUNTIME_HOSTED &&
                    effect.caller.owner == owner && effect.target in fileTargets
            else -> true
        }
    }
}
