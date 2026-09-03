package support.architecture

internal object EffectRules {
    private const val ENDPOINT_PUBLISHER_OWNER =
        "io/github/amichne/kast/indexer/IndexerEndpointDescriptorKt"
    private const val PROJECT_READ_EPOCH_SESSION_OWNER =
        "io/github/amichne/kast/workspace/intellij/read/AdmittedIdeProjectSession"
    private val topologySourceRootVfsSynchronizer = JvmMember.of(
        "io/github/amichne/kast/topology/intellij/InstalledTopologySourceRootVfsSynchronizer",
        "synchronize",
        "(Lio/github/amichne/kast/workspace/contract/PublishedWorkspace;Ljava/util/List;)" +
            "Lio/github/amichne/kast/topology/intellij/TopologySourceRootVfsSynchronization;",
    )
    private val topologySourceRootVfsRefresh = JvmMember.of(
        "com/intellij/openapi/vfs/VfsUtil",
        "markDirtyAndRefresh",
        "(ZZZ[Ljava/io/File;)V",
    )
    private val endpointFilesystemOwners = setOf(
        ENDPOINT_PUBLISHER_OWNER,
    )

    private val filesystemMutators = setOf(
        "copy",
        "createDirectories",
        "createDirectory",
        "createFile",
        "createTempFile",
        "delete",
        "deleteIfExists",
        "move",
        "newBufferedWriter",
        "newOutputStream",
        "write",
        "writeString",
    )
    private val psiMutators = setOf(
        "add",
        "addAfter",
        "addBefore",
        "delete",
        "deleteChildRange",
        "replace",
    )

    /**
     * Proof transition: `(ModuleRole, JvmMember, JvmMember) -> Set<ForbiddenEffect>`.
     *
     * Establishes the total finite effect classification for one JVM reference. There is no
     * expected failure; raw owner and member names are interpreted only inside this boundary.
     */
    fun classify(
        moduleRole: ModuleRole,
        caller: JvmMember,
        target: JvmMember,
    ): Set<ForbiddenEffect> = buildSet {
        val owner = target.owner.internalName
        val name = target.name.value
        if (
            moduleRole != ModuleRole.LEGACY_HOST &&
            (owner.startsWith("com/intellij/") ||
                owner.startsWith("org/jetbrains/kotlin/analysis/api/"))
        ) {
            add(ForbiddenEffect.INTELLIJ_PLATFORM)
        }
        if (owner == "com/intellij/openapi/roots/ProjectFileIndex") {
            add(ForbiddenEffect.PROJECT_FILE_INDEX_AUTHORITY)
        }
        if (
            owner == PROJECT_READ_EPOCH_SESSION_OWNER &&
            (name == "admit" || name.startsWith("admit-"))
        ) {
            add(ForbiddenEffect.PROJECT_READ_EPOCH_AUTHORITY)
        }
        if (
            moduleRole in setOf(ModuleRole.IDE_READ_ONLY, ModuleRole.IDE_HOST) &&
            (isProjectOpenAuthority(owner, name) ||
                moduleRole == ModuleRole.IDE_HOST && isAmbientProjectDiscovery(owner, name))
        ) {
            add(ForbiddenEffect.PROJECT_OPEN)
        }
        if (
            owner == "com/intellij/openapi/command/WriteCommandAction" ||
            (owner.startsWith("com/intellij/openapi/application/") && name.contains("writeAction", true)) ||
            (owner.startsWith("com/intellij/psi/") && name in psiMutators)
        ) {
            add(ForbiddenEffect.INTELLIJ_WRITE)
        }
        if (
            (owner == "java/nio/file/Files" && name in filesystemMutators) ||
            (owner.startsWith("kotlin/io/path/") && filesystemMutators.any(name::startsWith))
        ) {
            if (caller.owner.internalName in endpointFilesystemOwners) {
                add(ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE)
            } else {
                add(ForbiddenEffect.FILESYSTEM_WRITE)
                if (caller.isSourceMutationSurface()) add(ForbiddenEffect.SOURCE_FILESYSTEM_WRITE)
            }
        }
        if (isEndpointUdsAuthority(caller, target)) {
            add(ForbiddenEffect.UDS_BIND)
        }
        if (owner.startsWith("java/sql/") || owner.startsWith("org/sqlite/")) {
            add(ForbiddenEffect.JDBC)
        }
        if (moduleRole != ModuleRole.LEGACY_HOST && owner.startsWith("org/gradle/")) {
            add(ForbiddenEffect.GRADLE_PLATFORM)
        }
        val hostedReadEffects = HostedReadForbiddenAuthority.classify(moduleRole, target).let { effects ->
            if (
                ForbiddenEffect.RECURSIVE_VFS_REFRESH in effects &&
                caller == topologySourceRootVfsSynchronizer &&
                target == topologySourceRootVfsRefresh
            ) {
                effects - ForbiddenEffect.RECURSIVE_VFS_REFRESH +
                    ForbiddenEffect.TOPOLOGY_SOURCE_ROOT_VFS_SYNCHRONIZATION
            } else {
                effects
            }
        }
        if (isEndpointDescriptorReadBack(caller, target)) {
            add(ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE)
            addAll(hostedReadEffects - ForbiddenEffect.PHYSICAL_SOURCE_READ)
        } else {
            addAll(hostedReadEffects)
        }
        if (
            moduleRole in setOf(ModuleRole.IDE_READ_ONLY, ModuleRole.INTELLIJ_READ_ADAPTER) &&
            isWorkspaceTransitionAuthority(owner, name)
        ) {
            add(ForbiddenEffect.WORKSPACE_TRANSITION)
        }
        if (moduleRole != ModuleRole.LEGACY_HOST && isGraphBuildAuthority(owner, name)) {
            add(ForbiddenEffect.GRAPH_BUILD)
        }
        if (moduleRole != ModuleRole.LEGACY_HOST && isProcessControlAuthority(owner, name)) {
            add(ForbiddenEffect.PROCESS_CONTROL)
        }
        if (
            owner == "io/github/amichne/kast/api/contract/AnalysisBackend" ||
            owner == "io/github/amichne/kast/api/contract/CloseableAnalysisBackend"
        ) {
            add(ForbiddenEffect.ANALYSIS_BACKEND)
        }
        if (
            moduleRole == ModuleRole.IDE_READ_ONLY &&
            owner.startsWith("io/github/amichne/kast/change/")
        ) {
            add(ForbiddenEffect.MUTATION_AUTHORITY)
        }
        if (
            moduleRole == ModuleRole.IDE_READ_ONLY &&
            owner.startsWith("io/github/amichne/kast/topology/")
        ) {
            add(ForbiddenEffect.TOPOLOGY_AUTHORITY)
        }
        if (moduleRole == ModuleRole.IDE_READ_ONLY && isIsolatedRuntimeAuthority(owner)) {
            add(ForbiddenEffect.ISOLATED_RUNTIME)
        }
        if (owner == "io/github/amichne/kast/topology/build/TopologyBuildAuthority") {
            add(ForbiddenEffect.TOPOLOGY_BUILD_AUTHORITY)
        }
    }

    private fun JvmMember.isSourceMutationSurface(): Boolean = owner.internalName.let { callerOwner ->
        callerOwner == "io/github/amichne/kast/api/io/LocalDiskFileOperations"
    }

    private fun isEndpointDescriptorReadBack(caller: JvmMember, target: JvmMember): Boolean =
        caller.owner.internalName == ENDPOINT_PUBLISHER_OWNER &&
            caller.name.value == "readBack" &&
            target.owner.internalName == "java/nio/file/Files" &&
            target.name.value == "readString"

    private fun isEndpointUdsAuthority(caller: JvmMember, target: JvmMember): Boolean = when (
        caller.owner.internalName
    ) {
        "io/github/amichne/kast/indexer/InstalledIndexerTransport\$Companion" ->
            target.owner.internalName == "java/nio/channels/ServerSocketChannel" &&
                target.name.value in setOf("open", "bind")
        "io/github/amichne/kast/indexer/InstalledIndexerTransport" ->
            target.owner.internalName == "java/nio/channels/ServerSocketChannel" &&
                target.name.value == "accept"
        else -> false
    }

    private fun isProjectOpenAuthority(owner: String, name: String): Boolean =
        owner == "com/intellij/openapi/project/ex/ProjectManagerEx" && name == "openProject" ||
            owner == "com/intellij/ide/impl/ProjectUtil" && name in setOf("openOrImport", "openProject") ||
            owner == "com/intellij/ide/impl/OpenProjectTask"

    private fun isAmbientProjectDiscovery(owner: String, name: String): Boolean =
        owner == "com/intellij/openapi/project/ProjectManager" &&
            name in setOf("getInstance", "getOpenProjects")

    private fun isIsolatedRuntimeAuthority(owner: String): Boolean =
        owner.startsWith("io/github/amichne/kast/indexer/") ||
            owner.startsWith("io/github/amichne/kast/cli/runtime/") ||
            owner.startsWith("io/github/amichne/kast/distribution/managed/") ||
            owner.startsWith("io/github/amichne/kast/runtime/composition/")

    private fun isGraphBuildAuthority(owner: String, name: String): Boolean =
        (owner == "org/gradle/tooling/ProjectConnection" &&
            name in setOf("action", "model", "newBuild")) ||
            owner in setOf(
                "org/gradle/tooling/BuildActionExecuter",
                "org/gradle/tooling/BuildLauncher",
                "org/gradle/tooling/ModelBuilder",
            )

    private fun isWorkspaceTransitionAuthority(owner: String, name: String): Boolean =
        owner.endsWith("/WorkspaceTransitionPort") && name !in setOf("<init>", "<type>") ||
            owner.endsWith("/WorkspaceTransitionRequester") && name in setOf("reconcile", "mutate") ||
            owner.endsWith("/WorkspaceTransitionIngress") && name in setOf("reconcile", "mutate")

    private fun isProcessControlAuthority(owner: String, name: String): Boolean =
        owner == "java/lang/ProcessBuilder" ||
            (owner == "java/lang/Runtime" && name == "exec") ||
            (owner == "java/lang/Process" && name in setOf("destroy", "destroyForcibly")) ||
            (owner == "java/lang/ProcessHandle" && name in setOf("destroy", "destroyForcibly")) ||
            (owner == "com/intellij/execution/process/ProcessHandler" &&
                name in setOf("destroyProcess", "detachProcess", "killProcess"))

}
