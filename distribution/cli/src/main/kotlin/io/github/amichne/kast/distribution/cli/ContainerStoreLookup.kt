package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal fun ContainerStore.enumerate(): Refinement<List<ContainerStoreEntry>, ContainerFailure> = containerFiles {
    if (Files.notExists(containers, NOFOLLOW_LINKS)) return@containerFiles Refinement.Refined(emptyList())
    when (val private = admitPrivateContainerDirectory(containers)) {
        is Refinement.Rejected -> return@containerFiles private
        is Refinement.Refined -> Unit
    }
    Files.newDirectoryStream(containers).use { readEntries(it) }
}

private fun ContainerStore.readEntries(
    entries: java.nio.file.DirectoryStream<Path>
): Refinement<List<ContainerStoreEntry>, ContainerFailure> {
    val result = mutableListOf<ContainerStoreEntry>()
    var count = 0
    for (directory in entries) {
        val name = directory.fileName.toString()
        if (name.startsWith(".creating-") || name.startsWith(".removing-") || containerFinderMetadata(directory))
            continue
        count += 1
        if (count > MAXIMUM_CONTAINER_ENTRIES) return Refinement.Rejected(ContainerFailure.CAPACITY_EXCEEDED)
        result +=
            when (val admitted = readWorkspace(directory)) {
                is Refinement.Rejected -> ContainerStoreEntry.Rejected(directory, admitted.failure)
                is Refinement.Refined -> ContainerStoreEntry.Admitted(admitted.value)
            }
    }
    return Refinement.Refined(result)
}

internal fun containerFinderMetadata(path: Path): Boolean =
    path.fileName.toString() == ".DS_Store" &&
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
        Files.getOwner(path, NOFOLLOW_LINKS) == Files.getOwner(path.parent, NOFOLLOW_LINKS)

/** Read-only workspace selection never publishes records or repairs their generations. */
internal fun ContainerStore.select(selector: String): Refinement<AdmittedContainer, ContainerFailure> = containerFiles {
    if (
        ContainerId.parse(selector) is Refinement.Refined && Files.exists(containers.resolve(selector), NOFOLLOW_LINKS)
    ) {
        return@containerFiles readGenerationAt(containers.resolve(selector))
    }
    when (val all = enumerate()) {
        is Refinement.Rejected -> all
        is Refinement.Refined ->
            selectWorkspace(
                all.value
                    .filterIsInstance<ContainerStoreEntry.Admitted>()
                    .map { it.workspace }
                    .filter { it.name.text == selector },
                ContainerFailure.INVALID_RECORD,
            )
    }
}

/** Direct root lookup does not enumerate or admit unrelated runtime generations. */
internal fun ContainerStore.workspace(root: Path): Refinement<AdmittedWorkspace, ContainerFailure> = containerFiles {
    if (!validWorkspace(root)) return@containerFiles Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
    val directory = containers.resolve(ContainerId.forWorkspace(root).text)
    if (Files.exists(directory, NOFOLLOW_LINKS)) return@containerFiles readExactWorkspace(directory, root)
    when (val all = enumerate()) {
        is Refinement.Rejected -> all
        is Refinement.Refined -> {
            val legacy =
                all.value
                    .filterIsInstance<ContainerStoreEntry.Admitted>()
                    .map { it.workspace }
                    .filter { it.workspace == root }
            when (legacy.size) {
                0 -> Refinement.Rejected(ContainerFailure.NOT_FOUND)
                1 -> Refinement.Refined(legacy.single())
                else -> Refinement.Rejected(ContainerFailure.AMBIGUOUS_WORKSPACE)
            }
        }
    }
}

internal fun ContainerStore.route(directory: Path): Refinement<AdmittedContainer, ContainerFailure> = containerFiles {
    when (val discovered = FilesystemCanonicalRootDiscovery.discover(directory)) {
        is CanonicalRootDiscovery.Rejected -> Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE)
        is CanonicalRootDiscovery.Discovered ->
            when (val owned = workspace(discovered.root.path)) {
                is Refinement.Rejected -> owned
                is Refinement.Refined -> admitGeneration(owned.value)
            }
    }
}

private fun ContainerStore.readGenerationAt(directory: Path): Refinement<AdmittedContainer, ContainerFailure> =
    when (val workspace = readWorkspace(directory)) {
        is Refinement.Rejected -> workspace
        is Refinement.Refined -> admitGeneration(workspace.value)
    }

private fun ContainerStore.readExactWorkspace(
    directory: Path,
    root: Path,
): Refinement<AdmittedWorkspace, ContainerFailure> =
    when (val owned = readWorkspace(directory)) {
        is Refinement.Rejected -> owned
        is Refinement.Refined ->
            if (owned.value.workspace == root) owned else Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }

private fun ContainerStore.selectWorkspace(
    matches: List<AdmittedWorkspace>,
    ambiguous: ContainerFailure,
): Refinement<AdmittedContainer, ContainerFailure> =
    when (matches.size) {
        0 -> Refinement.Rejected(ContainerFailure.NOT_FOUND)
        1 -> admitGeneration(matches.single())
        else -> Refinement.Rejected(ambiguous)
    }
