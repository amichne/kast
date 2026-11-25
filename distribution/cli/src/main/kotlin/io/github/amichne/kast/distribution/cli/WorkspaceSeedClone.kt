package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.io.UncheckedIOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable

@Serializable
internal enum class WorkspaceSeedCloneStage {
    SOURCE_ADMISSION,
    CLONE,
}

internal sealed interface WorkspaceSeedClone {
    data object Completed : WorkspaceSeedClone

    data object Unsupported : WorkspaceSeedClone

    data class Failed(val stage: WorkspaceSeedCloneStage) : WorkspaceSeedClone
}

/**
 * The file system records and the indexes over them, which are valid only together. Every other system directory holds
 * one project's model or one runtime's paths.
 */
internal val WORKSPACE_SEED_STORAGE = listOf("caches", "index")

/** APFS clonefile never falls back to a byte copy or a hard link. Other platforms remain unqualified. */
internal fun cloneWorkspaceSystem(source: Path, destination: Path): WorkspaceSeedClone {
    if (!System.getProperty("os.name").startsWith("Mac")) return WorkspaceSeedClone.Unsupported
    var stage = WorkspaceSeedCloneStage.SOURCE_ADMISSION
    return try {
        if (admitPrivateContainerDirectory(source) is Refinement.Rejected) return WorkspaceSeedClone.Failed(stage)
        if (
            Files.getFileStore(source).type() != "apfs" || Files.getFileStore(source) != Files.getFileStore(destination)
        )
            return WorkspaceSeedClone.Unsupported
        val linker = Linker.nativeLinker()
        val symbol = linker.defaultLookup().find("clonefile")
        if (symbol.isEmpty) return WorkspaceSeedClone.Unsupported
        val clone = linker.downcallHandle(symbol.get(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
        val paths =
            when (val selected = workspaceSeedFiles(source)) {
                is Refinement.Rejected -> return WorkspaceSeedClone.Failed(stage)
                is Refinement.Refined -> selected.value
            }
        stage = WorkspaceSeedCloneStage.CLONE
        cloneWorkspaceFiles(source, destination, paths, clone)
    } catch (_: IOException) {
        WorkspaceSeedClone.Failed(stage)
    } catch (_: UncheckedIOException) {
        WorkspaceSeedClone.Failed(stage)
    } catch (_: SecurityException) {
        WorkspaceSeedClone.Failed(stage)
    }
}

private fun workspaceSeedFiles(source: Path): Refinement<List<Path>, ContainerFailure> {
    val paths = mutableListOf<Path>()
    for (storage in WORKSPACE_SEED_STORAGE) {
        val remaining = MAXIMUM_PROFILE_FILES + 1 - paths.size
        Files.walk(source.resolve(storage)).use { walk -> paths += walk.limit(remaining.toLong()).toList() }
        if (paths.size > MAXIMUM_PROFILE_FILES) return Refinement.Rejected(ContainerFailure.CAPACITY_EXCEEDED)
    }
    val owner = Files.getOwner(source, NOFOLLOW_LINKS)
    for (path in paths) {
        if (Files.getOwner(path, NOFOLLOW_LINKS) != owner) return Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS))
            return Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    }
    return Refinement.Refined(paths)
}

private fun cloneWorkspaceFiles(
    source: Path,
    destination: Path,
    paths: List<Path>,
    clone: MethodHandle,
): WorkspaceSeedClone =
    Arena.ofConfined().use { arena ->
        for (path in paths) {
            val target = destination.resolve(source.relativize(path))
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                Files.createDirectory(target, PosixFilePermissions.asFileAttribute(containerDirectoryMode))
                continue
            }
            val result =
                clone.invokeWithArguments(
                    arena.allocateFrom(path.toString()),
                    arena.allocateFrom(target.toString()),
                    0,
                )
            if (result != 0) return WorkspaceSeedClone.Failed(WorkspaceSeedCloneStage.CLONE)
        }
        WorkspaceSeedClone.Completed
    }

/** Only the unpublished recipient is erased; failed cleanup rejects creation instead of publishing partial indexes. */
internal fun discardWorkspaceSeed(system: Path): Refinement<Unit, ContainerFailure> {
    when (val admitted = admitPrivateContainerDirectory(system)) {
        is Refinement.Rejected -> return admitted
        is Refinement.Refined -> Unit
    }
    try {
        val paths = Files.walk(system).use { it.limit(MAXIMUM_PROFILE_FILES + 1).toList() }
        if (paths.size > MAXIMUM_PROFILE_FILES) return Refinement.Rejected(ContainerFailure.CAPACITY_EXCEEDED)
        for (path in paths.sortedDescending()) {
            if (path == system) continue
            Files.delete(path)
        }
    } catch (failure: UncheckedIOException) {
        throw IOException(failure)
    }
    return Refinement.Refined(Unit)
}
