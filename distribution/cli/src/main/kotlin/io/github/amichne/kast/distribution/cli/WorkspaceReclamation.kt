package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** The detached-directory receipt survives erasure; it never authorizes repository removal. */
@Serializable internal data class WorkspaceReclamation(val workspace: String, val removal: ManagedWorkspaceRemoval)

/** Opportunistic cleanup cannot turn an unrelated unreadable record into a failed semantic request. */
internal fun reclaimVanishedWorkspaces(store: ContainerStore) {
    reportReclamation(
        containerFiles {
            if (Files.notExists(store.containers, NOFOLLOW_LINKS)) return@containerFiles Refinement.Refined(Unit)
            when (val private = admitPrivateContainerDirectory(store.containers)) {
                is Refinement.Rejected -> return@containerFiles private
                is Refinement.Refined -> Unit
            }
            resumeWorkspaceReclamations(store)
            when (val entries = store.enumerate()) {
                is Refinement.Rejected -> entries
                is Refinement.Refined -> {
                    reclaimEntries(store, entries.value)
                    Refinement.Refined(Unit)
                }
            }
        }
    )
}

private fun reclaimEntries(store: ContainerStore, entries: List<ContainerStoreEntry>) {
    for (entry in entries) {
        if (
            entry is ContainerStoreEntry.Admitted &&
                Files.notExists(entry.workspace.workspace, NOFOLLOW_LINKS) &&
                entry.workspace.generation == ContainerGenerationSource.Current
        ) {
            reportReclamation(reclaimWorkspace(store, entry.workspace))
        }
    }
}

private fun reclaimWorkspace(store: ContainerStore, workspace: AdmittedWorkspace): Refinement<Unit, ContainerFailure> =
    ContainerQueryLease.whileReclaiming(store, workspace.id) {
        reclamationFiles {
            if (!Files.notExists(workspace.workspace, NOFOLLOW_LINKS)) return@reclamationFiles Refinement.Refined(Unit)
            val path = reclamationPath(store, workspace.id)
            if (Files.exists(path, NOFOLLOW_LINKS)) return@reclamationFiles resumeReclamation(store, path)
            val proof =
                captureRetiredWorkspaceRemoval(store, workspace) {
                    System.err.println(managementJson.encodeToString(it))
                }
            val receipt = WorkspaceReclamation(workspace.workspace.toString(), proof)
            val staged = store.containers.resolve(".creating-reclamation-${java.util.UUID.randomUUID()}")
            try {
                writeContainerRecord(staged, receipt)
                Files.move(staged, path, ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(staged)
            }
            finishReclamation(store, receipt, path)
        }
    }

private fun resumeWorkspaceReclamations(store: ContainerStore) {
    val receipts =
        Files.newDirectoryStream(store.containers, ".removing-*.json").use { entries ->
            entries.take(MAXIMUM_CONTAINER_ENTRIES + 1)
        }
    if (receipts.size > MAXIMUM_CONTAINER_ENTRIES) {
        reportReclamation(Refinement.Rejected(ContainerFailure.CAPACITY_EXCEEDED))
        return
    }
    for (path in receipts) {
        val raw = path.fileName.toString().removePrefix(".removing-").removeSuffix(".json")
        reportReclamation(
            when (val id = ContainerId.parse(raw)) {
                is Refinement.Rejected -> id
                is Refinement.Refined ->
                    ContainerQueryLease.whileReclaiming(store, id.value) {
                        reclamationFiles { resumeReclamation(store, path) }
                    }
            }
        )
    }
}

private fun resumeReclamation(store: ContainerStore, path: Path): Refinement<Unit, ContainerFailure> {
    if (Files.notExists(path, NOFOLLOW_LINKS)) return Refinement.Refined(Unit)
    if (
        Files.isSymbolicLink(path) ||
            Files.getOwner(path, NOFOLLOW_LINKS) != Files.getOwner(store.containers) ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) !=
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
    )
        return Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    return when (val read = readContainerRecord<WorkspaceReclamation>(path)) {
        is Refinement.Rejected -> read
        is Refinement.Refined -> finishReclamation(store, read.value, path)
    }
}

private fun finishReclamation(
    store: ContainerStore,
    receipt: WorkspaceReclamation,
    path: Path,
): Refinement<Unit, ContainerFailure> {
    val id =
        when (val parsed = ContainerId.parse(receipt.removal.id)) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    val workspace = Path.of(receipt.workspace)
    if (path != reclamationPath(store, id) || !store.validWorkspace(workspace))
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    if (!Files.notExists(workspace, NOFOLLOW_LINKS)) return Refinement.Rejected(ContainerFailure.WORKSPACE_EXISTS)
    val original = store.containers.resolve(id.text)
    val detached = store.containers.resolve(".removing-${id.text}")
    if (Files.exists(original, NOFOLLOW_LINKS)) {
        if (Files.exists(detached, NOFOLLOW_LINKS) || uninstallFilesystemIdentity(original) != receipt.removal.identity)
            return Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
        Files.move(original, detached, ATOMIC_MOVE)
    }
    removeWorkspaceProfile(detached, receipt.removal)
    Files.delete(path)
    containerProgress(ContainerStage.RECLAMATION, ContainerStageOutcome.COMPLETED)
    return Refinement.Refined(Unit)
}

private fun reclamationPath(store: ContainerStore, id: ContainerId): Path =
    store.containers.resolve(".removing-${id.text}.json")

private fun reclamationFiles(action: () -> Refinement<Unit, ContainerFailure>): Refinement<Unit, ContainerFailure> =
    containerFiles {
        try {
            action()
        } catch (_: ManagementRejected) {
            // The cleanup owner already emits the finite evidence rejection. Retain the receipt and profile.
            Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
        }
    }

private fun reportReclamation(outcome: Refinement<Unit, ContainerFailure>) {
    if (outcome is Refinement.Rejected) {
        System.err.println(containerJson.encodeToString(WorkspaceReclamationRejected(outcome.failure)))
    }
}

@Serializable
private data class WorkspaceReclamationRejected(
    val failure: ContainerFailure,
    val stage: ContainerStage = ContainerStage.RECLAMATION,
    val outcome: ContainerStageOutcome = ContainerStageOutcome.REJECTED,
)
