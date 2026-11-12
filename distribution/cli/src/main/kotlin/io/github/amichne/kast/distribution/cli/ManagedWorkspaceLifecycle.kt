package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal sealed interface WorkspaceScope {
    data object Current : WorkspaceScope

    data object All : WorkspaceScope

    data class Explicit(val directory: Path) : WorkspaceScope
}

internal fun selectWorkspaceScope(workspace: String?, all: Boolean): WorkspaceScope {
    if (all && workspace != null) throw com.github.ajalt.clikt.core.CliktError("--all conflicts with --workspace")
    return when {
        all -> WorkspaceScope.All
        workspace == null -> WorkspaceScope.Current
        else ->
            try {
                WorkspaceScope.Explicit(Path.of(workspace))
            } catch (_: java.nio.file.InvalidPathException) {
                throw com.github.ajalt.clikt.core.CliktError("invalid workspace path")
            }
    }
}

internal fun scopedWorkspaces(
    store: ContainerStore,
    scope: WorkspaceScope,
    directory: Path,
): Refinement<List<ContainerStoreEntry>, ContainerFailure> =
    when (scope) {
        WorkspaceScope.All -> store.enumerate()
        WorkspaceScope.Current -> workspaceEntry(store, directory)
        is WorkspaceScope.Explicit -> workspaceEntry(store, scope.directory.toAbsolutePath())
    }

private fun workspaceEntry(
    store: ContainerStore,
    directory: Path,
): Refinement<List<ContainerStoreEntry>, ContainerFailure> =
    when (val root = FilesystemCanonicalRootDiscovery.discover(directory)) {
        is CanonicalRootDiscovery.Rejected -> Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
        is CanonicalRootDiscovery.Discovered ->
            when (val owned = store.workspace(root.root.path)) {
                is Refinement.Rejected -> owned
                is Refinement.Refined -> Refinement.Refined(listOf(ContainerStoreEntry.Admitted(owned.value)))
            }
    }

/** Scoped stop holds exclusive request leases, but writes no shutdown fence or persistent disablement. */
internal fun stopManagedWorkspaces(
    store: ContainerStore,
    scope: WorkspaceScope,
    directory: Path,
    retire: (AdmittedContainer) -> Refinement<Unit, ContainerFailure>,
): Refinement<List<String>, ContainerFailure> = containerFiles {
    val entries =
        when (val selected = scopedWorkspaces(store, scope, directory)) {
            is Refinement.Rejected -> return@containerFiles selected
            is Refinement.Refined -> selected.value
        }
    val current =
        when (val migrated = migrateWorkspaceEntries(store, entries)) {
            is Refinement.Rejected -> return@containerFiles migrated
            is Refinement.Refined -> migrated.value
        }
    val containers =
        when (val admitted = admitWorkspaceGenerations(store, current)) {
            is Refinement.Rejected -> return@containerFiles admitted
            is Refinement.Refined -> admitted.value
        }
    ContainerQueryLease.whileAllIdle(containers) {
        when (val retired = retireGenerations(store, containers, retire)) {
            is Refinement.Rejected -> retired
            is Refinement.Refined -> Refinement.Refined(containers.map { it.workspace.toString() })
        }
    }
}

@Serializable
internal sealed interface WorkspaceStopOutcome {
    @Serializable @SerialName("STOPPED") data class Stopped(val workspaces: List<String>) : WorkspaceStopOutcome

    @Serializable @SerialName("REJECTED") data class Rejected(val failure: ContainerFailure) : WorkspaceStopOutcome
}

internal fun executeWorkspaceStop(
    root: Path,
    command: ManagementCommand.Stop,
    environment: Map<String, String>,
    output: ManagementOutput,
) {
    val session = OwnedChildSession()
    val result =
        when (val store = containerStore(root.resolveSibling("kast-workspaces"))) {
            is Refinement.Rejected -> store
            is Refinement.Refined ->
                stopManagedWorkspaces(store.value, command.scope, Path.of("").toAbsolutePath()) {
                    shutdownContainerHost(it, environment, session)
                }
        }
    val document =
        when (result) {
            is Refinement.Rejected -> WorkspaceStopOutcome.Rejected(result.failure)
            is Refinement.Refined -> WorkspaceStopOutcome.Stopped(result.value)
        }
    if (output.structured(command.json)) output.publish(managementJson.encodeToString<WorkspaceStopOutcome>(document))
    else
        when (document) {
            is WorkspaceStopOutcome.Stopped ->
                output.message(
                    "Stopped ${document.workspaces.size} workspace Hosts; the next semantic request may start them."
                )
            is WorkspaceStopOutcome.Rejected ->
                System.err.println("kast: stop: ${document.failure}; select --workspace PATH or --all")
        }
    if (result is Refinement.Rejected) kotlin.system.exitProcess(result.failure.exit)
}
