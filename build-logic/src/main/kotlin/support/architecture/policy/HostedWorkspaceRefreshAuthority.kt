package support.architecture

/** Explicit lifecycle authority is confined to one adapter and the exact asynchronous native entry points. */
internal object HostedWorkspaceRefreshAuthority {
    val owner = JvmClassName("io/github/amichne/kast/runtime/hosted/workspace/IntellijWorkspaceRefreshPort")
    private const val BUILDER = "com/intellij/openapi/externalSystem/importing/ImportSpecBuilder"
    private val importTargets = setOf(
        JvmMember.of(BUILDER, "<type>", ""),
        JvmMember.of(BUILDER, "<init>", "(Lcom/intellij/openapi/project/Project;Lcom/intellij/openapi/externalSystem/model/ProjectSystemId;)V"),
        JvmMember.of(BUILDER, "use", "(Lcom/intellij/openapi/externalSystem/service/execution/ProgressExecutionMode;)L$BUILDER;"),
        JvmMember.of(BUILDER, "withImportProjectData", "(Z)L$BUILDER;"),
        JvmMember.of(BUILDER, "withCallback", "(Lcom/intellij/openapi/externalSystem/service/project/ExternalProjectRefreshCallback;)L$BUILDER;"),
        JvmMember.of("com/intellij/openapi/externalSystem/util/ExternalSystemUtil", "refreshProject", "(Ljava/lang/String;L$BUILDER;)V"),
    )
    private val fileTargets = setOf(JvmMember.of("com/intellij/openapi/vfs/newvfs/RefreshQueue", "refresh",
        "(ZZLjava/lang/Runnable;[Lcom/intellij/openapi/vfs/VirtualFile;)V"),
        JvmMember.of("com/intellij/openapi/vfs/VfsUtil", "markDirty",
            "(ZZ[Lcom/intellij/openapi/vfs/VirtualFile;)Ljava/util/List;"),
    )

    fun retainsBoundary(effect: EffectObservation): Boolean {
        if (effect.caller.owner != owner) return true
        return when (effect.effect) {
            ForbiddenEffect.GRADLE_IMPORT -> effect.target in importTargets
            ForbiddenEffect.RECURSIVE_VFS_REFRESH -> effect.target in fileTargets
            else -> true
        }
    }
}
