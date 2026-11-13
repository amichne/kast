package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.TimeSource

/** One admitted semantic request owns this lease, never an MCP or RPC session. */
class ManagedWorkspaceQuery
private constructor(
    private val lease: ContainerQueryLease,
    val home: Path,
    val environment: Map<String, String>,
    private val allowance: ElapsedTimeAllowance,
    private val waiting: ContainerPreparationWait,
) : AutoCloseable {
    fun <T> prepare(action: (ElapsedTimeAllowance) -> T): Refinement<T, ContainerFailure> =
        withContainerPreparationLock(lease, allowance, waiting) { remaining ->
            when (val current = lease.admitGeneration()) {
                is Refinement.Rejected -> current
                is Refinement.Refined ->
                    when (val disposition = resumeContainerState(current.value)) {
                        is Refinement.Rejected -> disposition
                        is Refinement.Refined -> Refinement.Refined(action(remaining))
                    }
            }
        }

    override fun close() = lease.close()

    companion object {
        fun open(
            installation: Path,
            root: CanonicalRoot,
            inherited: Map<String, String>,
        ): Refinement<ManagedWorkspaceQuery, ContainerFailure> =
            open(installation, root, inherited, WorkspaceAccess.PREPARE)

        fun openExisting(
            installation: Path,
            root: CanonicalRoot,
            inherited: Map<String, String>,
        ): Refinement<ManagedWorkspaceQuery, ContainerFailure> =
            open(installation, root, inherited, WorkspaceAccess.RETAINED)

        private fun open(
            installation: Path,
            root: CanonicalRoot,
            inherited: Map<String, String>,
            access: WorkspaceAccess,
        ): Refinement<ManagedWorkspaceQuery, ContainerFailure> = containerFiles {
            val waiting = ManagedWorkspaceWait()
            val platform =
                when (val parsed = ContainerPlatform.current()) {
                    is Refinement.Rejected -> return@containerFiles parsed
                    is Refinement.Refined -> parsed.value
                }
            val product =
                when (val selected = InstalledProductRuntime.read(installation, platform)) {
                    is Refinement.Rejected -> return@containerFiles selected
                    is Refinement.Refined -> selected.value
                }
            ContainerQueryLease.duringStoreAdmission(product.runtime.root.parent.parent) {
                openSelected(installation, root, inherited, product, waiting, access)
            }
        }

        private fun openSelected(
            installation: Path,
            root: CanonicalRoot,
            inherited: Map<String, String>,
            product: InstalledProductRuntime,
            waiting: ContainerPreparationWait,
            access: WorkspaceAccess,
        ): Refinement<ManagedWorkspaceQuery, ContainerFailure> {
            val store = ContainerStore(product.runtime.root.parent.parent, product.runtime.manifest.platform)
            if ("KAST_MANAGED_HEADLESS_STATE" in inherited) return Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
            when (val current = product.admitCurrent()) {
                is Refinement.Rejected -> return current
                is Refinement.Refined -> Unit
            }
            val configuration =
                when (val parsed = containerPreparationConfiguration(installation, root.path, inherited)) {
                    is Refinement.Rejected -> return parsed
                    is Refinement.Refined -> parsed.value
                }
            if (access == WorkspaceAccess.PREPARE) reclaimVanishedWorkspaces(store)
            val workspace =
                when (val selected = admitWorkspace(store, root, product, configuration, waiting, access)) {
                    is Refinement.Rejected -> return selected
                    is Refinement.Refined -> selected.value
                }
            return when (val lease = ContainerQueryLease.acquire(workspace, store.platform)) {
                is Refinement.Rejected -> lease
                is Refinement.Refined -> bind(lease.value, product, configuration, inherited, waiting, access)
            }
        }

        private fun bind(
            lease: ContainerQueryLease,
            product: InstalledProductRuntime,
            configuration: ResolvedKastConfiguration,
            inherited: Map<String, String>,
            waiting: ContainerPreparationWait,
            access: WorkspaceAccess,
        ): Refinement<ManagedWorkspaceQuery, ContainerFailure> {
            var transferred = false
            try {
                when (val current = product.admitCurrent()) {
                    is Refinement.Rejected -> return current
                    is Refinement.Refined -> Unit
                }
                val generation =
                    when (access) {
                        WorkspaceAccess.PREPARE ->
                            prepareContainerGeneration(
                                lease,
                                product.runtime,
                                configuration.workspacePreparationWait,
                                waiting,
                            )
                        WorkspaceAccess.RETAINED -> lease.admitGeneration()
                    }
                val container =
                    when (generation) {
                        is Refinement.Rejected -> return generation
                        is Refinement.Refined -> generation.value
                    }
                if (container.runtime.archiveSha256 != product.runtime.archiveSha256)
                    return Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH)
                if (
                    !java.nio.file.Files.isDirectory(container.gradleHome) ||
                        container.gradleHome.toRealPath() != container.gradleHome
                )
                    return Refinement.Rejected(ContainerFailure.INVALID_GRADLE_HOME)
                val environment =
                    configuration.launchEnvironment().variables.filterKeys {
                        it !in setOf("KAST_INSTALL_IDEA_HOME", "KAST_CONFIGURATION_FILE", "KAST_MANAGED_HEADLESS_STATE")
                    } + containerEnvironment(container, inherited)
                val query =
                    ManagedWorkspaceQuery(
                        lease,
                        container.home,
                        environment,
                        configuration.workspacePreparationWait,
                        waiting,
                    )
                transferred = true
                return Refinement.Refined(query)
            } finally {
                if (!transferred) lease.close()
            }
        }
    }
}

private enum class WorkspaceAccess {
    PREPARE,
    RETAINED,
}

private fun admitWorkspace(
    store: ContainerStore,
    root: CanonicalRoot,
    product: InstalledProductRuntime,
    configuration: ResolvedKastConfiguration,
    waiting: ContainerPreparationWait,
    access: WorkspaceAccess,
): Refinement<AdmittedWorkspace, ContainerFailure> =
    when (val current = store.workspace(root.path)) {
        is Refinement.Refined ->
            when (access) {
                WorkspaceAccess.PREPARE -> migrateLegacyWorkspace(store, current.value)
                WorkspaceAccess.RETAINED -> current
            }
        is Refinement.Rejected ->
            if (current.failure != ContainerFailure.NOT_FOUND || access == WorkspaceAccess.RETAINED) current
            else createWorkspace(store, root, product, configuration, waiting)
    }

private fun createWorkspace(
    store: ContainerStore,
    root: CanonicalRoot,
    product: InstalledProductRuntime,
    configuration: ResolvedKastConfiguration,
    waiting: ContainerPreparationWait,
): Refinement<AdmittedWorkspace, ContainerFailure> {
    val gradle = configuration.launchEnvironment().variables["GRADLE_USER_HOME"]
    val creation =
        when (
            val admitted =
                store.admitCreation(ContainerCommand.Create(null, root.path.toString(), gradle, false), root.path)
        ) {
            is Refinement.Rejected ->
                return if (admitted.failure == ContainerFailure.WORKSPACE_EXISTS) store.workspace(root.path)
                else admitted
            is Refinement.Refined -> admitted.value
        }
    // Copying Host plugins never holds the registry lock. Only the winning complete profile is published.
    return store.withStagedCreation(creation, product.runtime) { staged ->
        withContainerPublicationLock(store.root, configuration.workspacePreparationWait, waiting) {
            when (val current = product.admitCurrent()) {
                is Refinement.Rejected -> current
                is Refinement.Refined -> publishWorkspace(store, staged)
            }
        }
    }
}

private fun publishWorkspace(
    store: ContainerStore,
    staged: StagedContainerCreation,
): Refinement<AdmittedWorkspace, ContainerFailure> =
    when (val current = store.workspace(staged.creation.workspace)) {
        is Refinement.Refined -> current
        is Refinement.Rejected ->
            if (current.failure != ContainerFailure.NOT_FOUND) current
            else {
                when (val created = store.publishCreation(staged)) {
                    is Refinement.Rejected -> created
                    is Refinement.Refined -> {
                        System.err.println("kast: trusting build code at ${staged.creation.workspace}")
                        Refinement.Refined(created.value.owned)
                    }
                }
            }
    }

private class ManagedWorkspaceWait : ContainerPreparationWait {
    private val started = TimeSource.Monotonic.markNow()

    override fun elapsed(): Duration = started.elapsedNow()

    override fun pause(duration: Duration) = Thread.sleep(duration.inWholeMilliseconds.coerceAtLeast(1))

    override fun admitActive(): Refinement<Unit, ContainerFailure> =
        if (Thread.currentThread().isInterrupted) Refinement.Rejected(ContainerFailure.CANCELLED_INTERRUPT)
        else Refinement.Refined(Unit)
}
