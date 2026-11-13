package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

internal fun retireManagedProduct(
    root: Path,
    home: Path,
    environment: Map<String, String>,
): Refinement<Unit, ContainerFailure> {
    val store =
        when (val selected = containerStore(root.resolveSibling("kast-workspaces"))) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    val lifecycle =
        when (
            val admitted =
                InstallationLifecycle.admitProduct(root, home, environment, store.platform, LifecycleExecution())
        ) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    val session = OwnedChildSession()
    val selectedEnvironment = environment + mapOf("KAST_INSTALL_ROOT" to root.toString(), "HOME" to home.toString())
    val outcome =
        retireManagedWorkspaces(store, lifecycle::fenceManagedProduct) { container ->
            shutdownContainerHost(container, selectedEnvironment, session)
        }
    if (outcome is Refinement.Refined) reclaimVanishedWorkspaces(store)
    return outcome
}

/** No fence or Host effect is allowed until every known workspace has an exclusive lease. */
internal fun retireManagedWorkspaces(
    store: ContainerStore,
    fence: () -> Refinement<Unit, ContainerFailure>,
    retire: (AdmittedContainer) -> Refinement<Unit, ContainerFailure>,
): Refinement<Unit, ContainerFailure> = retireSelectedWorkspaces(store, store::enumerate, fence, retire)

internal fun retireSelectedWorkspaces(
    store: ContainerStore,
    select: () -> Refinement<List<ContainerStoreEntry>, ContainerFailure>,
    fence: () -> Refinement<Unit, ContainerFailure>,
    retire: (AdmittedContainer) -> Refinement<Unit, ContainerFailure>,
): Refinement<Unit, ContainerFailure> {
    val entries =
        when (val selected = select()) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    when (val migrated = migrateWorkspaceEntries(store, entries)) {
        is Refinement.Rejected -> return migrated
        is Refinement.Refined -> Unit
    }
    return withContainerLock(store.root) {
        val current =
            when (val selected = select()) {
                is Refinement.Rejected -> return@withContainerLock selected
                is Refinement.Refined -> selected.value
            }
        val containers =
            when (val admitted = admitWorkspaceGenerations(store, current)) {
                is Refinement.Rejected -> return@withContainerLock admitted
                is Refinement.Refined -> admitted.value
            }
        ContainerQueryLease.whileAllIdle(containers) {
            when (val fenced = fence()) {
                is Refinement.Rejected -> fenced
                is Refinement.Refined -> retireGenerations(store, containers, retire)
            }
        }
    }
}

internal fun migrateWorkspaceEntries(
    store: ContainerStore,
    entries: List<ContainerStoreEntry>,
): Refinement<List<ContainerStoreEntry>, ContainerFailure> {
    val current = mutableListOf<ContainerStoreEntry>()
    for (entry in entries) when (entry) {
        is ContainerStoreEntry.Rejected -> return rejectWorkspaceEntry(entry.directory, entry.failure)
        is ContainerStoreEntry.Admitted ->
            when (val migrated = migrateLegacyWorkspace(store, entry.workspace)) {
                is Refinement.Rejected -> return migrated
                is Refinement.Refined -> current += ContainerStoreEntry.Admitted(migrated.value)
            }
    }
    return Refinement.Refined(current)
}

internal fun retireGenerations(
    store: ContainerStore,
    containers: List<AdmittedContainer>,
    retire: (AdmittedContainer) -> Refinement<Unit, ContainerFailure>,
): Refinement<Unit, ContainerFailure> {
    for (container in containers) {
        when (val retired = retire(container)) {
            is Refinement.Rejected -> return retired
            is Refinement.Refined -> Unit
        }
        when (val recorded = store.recordRetirement(container)) {
            is Refinement.Rejected -> return recorded
            is Refinement.Refined -> Unit
        }
    }
    return Refinement.Refined(Unit)
}

internal fun admitWorkspaceGenerations(
    store: ContainerStore,
    entries: List<ContainerStoreEntry>,
): Refinement<List<AdmittedContainer>, ContainerFailure> {
    val containers = mutableListOf<AdmittedContainer>()
    for (entry in entries) {
        when (entry) {
            is ContainerStoreEntry.Rejected -> return rejectWorkspaceEntry(entry.directory, entry.failure)
            is ContainerStoreEntry.Admitted ->
                when (val generation = store.admitGeneration(entry.workspace)) {
                    is Refinement.Rejected -> return rejectWorkspaceEntry(entry.workspace.directory, generation.failure)
                    is Refinement.Refined -> containers += generation.value
                }
        }
    }
    return Refinement.Refined(containers.sortedBy { it.id.text })
}
