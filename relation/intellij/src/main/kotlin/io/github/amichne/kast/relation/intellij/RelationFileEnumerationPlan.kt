package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import java.nio.file.Path

/** Detached roots are selected before any VFS lookup or inventory capacity accounting. */
internal sealed interface RelationFileEnumerationPlan {
    data class Unavailable(val reason: RelationFileEnumerationDecline) : RelationFileEnumerationPlan

    class Selected
    internal constructor(
        val roots: List<RelationFileEnumerationRoot>,
        val admitsPath: (Path) -> Boolean,
    ) : RelationFileEnumerationPlan

    companion object {
        fun compile(
            scope: SymbolSearchScope,
            roots: List<ModelOwnedSourceRoot>,
            workspace: String,
            constraints: SymbolDiscoveryConstraints,
            admitsPath: (Path) -> Boolean,
        ): RelationFileEnumerationPlan {
            if (scope is SymbolSearchScope.Workspace) {
                if (scope.libraries == SymbolLibraryPolicy.INCLUDE) {
                    return Unavailable(RelationFileEnumerationDecline.LIBRARIES_INCLUDED)
                }
                if (constraints.directory == null) {
                    return Unavailable(RelationFileEnumerationDecline.WORKSPACE_UNBOUNDED)
                }
            }
            if (scope is SymbolSearchScope.ExactFile) {
                return Selected(listOf(RelationFileEnumerationRoot.File(Path.of(scope.file.value))), admitsPath)
            }
            return selectedRoots(roots, workspace, constraints, admitsPath)
        }

        private fun selectedRoots(
            roots: List<ModelOwnedSourceRoot>,
            workspace: String,
            constraints: SymbolDiscoveryConstraints,
            admitsPath: (Path) -> Boolean,
        ): Selected {
            val restriction = constraints.directory
            val requested = restriction?.let { Path.of(workspace).resolve(it.directory.value).normalize() }
            val selected =
                roots
                    .mapNotNull { root ->
                        val source = Path.of(root.sourceRoot.value)
                        when {
                            requested == null ->
                                RelationFileEnumerationRoot.Directory(source, RelationDirectoryTraversal.DESCENDANTS)
                            requested.startsWith(source) ->
                                RelationFileEnumerationRoot.Directory(
                                    requested,
                                    if (restriction.containment == SymbolDiscoveryContainment.DIRECT)
                                        RelationDirectoryTraversal.DIRECT
                                    else RelationDirectoryTraversal.DESCENDANTS,
                                )
                            restriction.containment == SymbolDiscoveryContainment.DESCENDANTS &&
                                source.startsWith(requested) ->
                                RelationFileEnumerationRoot.Directory(source, RelationDirectoryTraversal.DESCENDANTS)
                            else -> null
                        }
                    }
                    .distinct()
                    .sortedBy { it.path.toString() }
            // Remove only duplicate descendant walks. Excluded owners can still contain admitted descendants.
            val minimal = selected.filter { root ->
                selected.none { other ->
                    other !== root &&
                        other.traversal == RelationDirectoryTraversal.DESCENDANTS &&
                        root.path != other.path &&
                        root.path.startsWith(other.path)
                }
            }
            return Selected(minimal, admitsPath)
        }
    }
}

internal enum class RelationDirectoryTraversal {
    DIRECT,
    DESCENDANTS,
}

internal sealed interface RelationFileEnumerationRoot {
    val path: Path

    data class File(override val path: Path) : RelationFileEnumerationRoot

    data class Directory(override val path: Path, val traversal: RelationDirectoryTraversal) :
        RelationFileEnumerationRoot
}

internal enum class RelationFileEnumerationDecline {
    LIBRARIES_INCLUDED,
    WORKSPACE_UNBOUNDED,
    WORK_LIMIT,
    TIME_LIMIT,
    FILE_CAPACITY,
    ROOT_UNAVAILABLE,
    FILE_INVALID,
    PATH_UNAVAILABLE,
    PATH_MISMATCH,
    ROOT_KIND_MISMATCH,
    FILE_ID_UNAVAILABLE,
    FILE_ID_COLLISION,
}

internal fun RelationFileEnumerationDecline.counter():
    io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter =
    when (this) {
        RelationFileEnumerationDecline.LIBRARIES_INCLUDED ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                .RELATION_FILE_ENUMERATION_LIBRARIES_INCLUDED
        RelationFileEnumerationDecline.WORKSPACE_UNBOUNDED ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                .RELATION_FILE_ENUMERATION_WORKSPACE_UNBOUNDED
        RelationFileEnumerationDecline.WORK_LIMIT ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter.RELATION_FILE_ENUMERATION_WORK_LIMIT
        RelationFileEnumerationDecline.TIME_LIMIT ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter.RELATION_FILE_ENUMERATION_TIME_LIMIT
        RelationFileEnumerationDecline.FILE_CAPACITY ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter.RELATION_FILE_ENUMERATION_FILE_CAPACITY
        RelationFileEnumerationDecline.ROOT_UNAVAILABLE ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                .RELATION_FILE_ENUMERATION_ROOT_UNAVAILABLE
        RelationFileEnumerationDecline.FILE_INVALID ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter.RELATION_FILE_ENUMERATION_FILE_INVALID
        RelationFileEnumerationDecline.PATH_UNAVAILABLE ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                .RELATION_FILE_ENUMERATION_PATH_UNAVAILABLE
        RelationFileEnumerationDecline.PATH_MISMATCH ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter.RELATION_FILE_ENUMERATION_PATH_MISMATCH
        RelationFileEnumerationDecline.ROOT_KIND_MISMATCH ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                .RELATION_FILE_ENUMERATION_ROOT_KIND_MISMATCH
        RelationFileEnumerationDecline.FILE_ID_UNAVAILABLE ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                .RELATION_FILE_ENUMERATION_FILE_ID_UNAVAILABLE
        RelationFileEnumerationDecline.FILE_ID_COLLISION ->
            io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
                .RELATION_FILE_ENUMERATION_FILE_ID_COLLISION
    }
