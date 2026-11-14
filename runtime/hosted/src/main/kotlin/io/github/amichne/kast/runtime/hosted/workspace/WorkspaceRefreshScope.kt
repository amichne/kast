package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.runtime.hosted.isWithin
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import kotlinx.serialization.Serializable

/**
 * Imported-model facts that bound read-preparation disk refresh. Paths are absolute, system-independent VFS paths.
 *
 * The headless Host has no native watcher, so every refreshed root is re-listed. Read admission depends only on source
 * content, library roots, and Gradle model inputs; other trees under the workspace root carry no read authority.
 */
internal data class WorkspaceRefreshInputs(
    /** Declared source, test, and resource roots, including roots that do not exist yet. */
    val sourceRoots: Collection<String>,
    /** Local files or directories that back library class and source roots. */
    val libraryRoots: Collection<String>,
    /** Linked Gradle module directories; their build scripts are direct children. */
    val modelDirectories: Collection<String>,
    /** Gradle settings inputs that the IDE's auto-import awareness declares. */
    val modelFiles: Collection<String>,
)

/** Disk coverage of one read-preparation refresh, never wider than the workspace root. */
internal sealed interface WorkspaceRefreshScope {
    /** No imported source root is known, so imported ownership cannot bound the refresh. */
    data object WholeWorkspace : WorkspaceRefreshScope

    /**
     * [recursive] covers every existing read input. [flat] lists one level of directories whose children are model
     * inputs or the next missing segment of a declared source root; the workspace root is always included.
     */
    data class Imported(val recursive: List<String>, val flat: List<String>) : WorkspaceRefreshScope
}

/** `exists` reports a VFS-visible file or directory; the workspace root itself always exists. */
internal fun planWorkspaceRefresh(
    root: CanonicalWorkspaceRoot,
    inputs: WorkspaceRefreshInputs,
    exists: (String) -> Boolean,
): WorkspaceRefreshScope {
    val sources = inputs.sourceRoots.filter { it.isWithin(root) }.toSet()
    if (sources.isEmpty()) return WorkspaceRefreshScope.WholeWorkspace
    fun present(path: String) = path == root.value || exists(path)
    fun nearestPresent(path: String): String =
        generateSequence(path) { current -> current.substringBeforeLast('/').takeIf { it.isWithin(root) } }
            .first(::present)
    val recursive =
        (sources + inputs.libraryRoots.filter { it.isWithin(root) }).filter(::present).sorted().fold(
            emptyList<String>()
        ) { kept, path ->
            if (kept.any { path.isAtOrBelow(it) }) kept else kept + path
        }
    val flat = buildSet {
        add(root.value)
        sources.filterNot(::present).mapTo(this, ::nearestPresent)
        inputs.modelDirectories.filter { it.isWithin(root) }.mapTo(this, ::nearestPresent)
        inputs.modelFiles
            .filter { it.isWithin(root) && it != root.value }
            .mapTo(this) { nearestPresent(it.substringBeforeLast('/')) }
    }
    return WorkspaceRefreshScope.Imported(
        recursive = recursive,
        flat = flat.filterNot { directory -> recursive.any { directory.isAtOrBelow(it) } }.sorted(),
    )
}

private fun String.isAtOrBelow(ancestor: String): Boolean = this == ancestor || startsWith("$ancestor/")

/** One native refresh session: one recursion mode over resolved VFS roots. */
internal class RefreshSessionRoots(val recursive: Boolean, val roots: List<com.intellij.openapi.vfs.VirtualFile>)

@Serializable
internal enum class WorkspaceRefreshScopeKind {
    IMPORTED,
    WHOLE_WORKSPACE,
}

/** Bounded counts only; refreshed paths stay out of the Host log. */
@Serializable
internal data class WorkspaceRefreshScopeObservation(
    val scope: WorkspaceRefreshScopeKind,
    val recursiveRoots: Int,
    val flatDirectories: Int,
)

internal fun WorkspaceRefreshScope.observation(): WorkspaceRefreshScopeObservation =
    when (this) {
        WorkspaceRefreshScope.WholeWorkspace ->
            WorkspaceRefreshScopeObservation(
                WorkspaceRefreshScopeKind.WHOLE_WORKSPACE,
                recursiveRoots = 1,
                flatDirectories = 0,
            )
        is WorkspaceRefreshScope.Imported ->
            WorkspaceRefreshScopeObservation(WorkspaceRefreshScopeKind.IMPORTED, recursive.size, flat.size)
    }
