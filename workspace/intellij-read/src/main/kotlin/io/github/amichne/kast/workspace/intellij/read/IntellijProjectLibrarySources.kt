package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import io.github.amichne.kast.kernel.Refinement

sealed interface LibrarySourceRootFailure<out Failure> {
    data object Unavailable : LibrarySourceRootFailure<Nothing>

    data object Ambiguous : LibrarySourceRootFailure<Nothing>

    data class WorkRejected<Failure>(val failure: Failure) : LibrarySourceRootFailure<Failure>
}

/** Source roots come from the selected binary's live library entries within the caller's read action. */
object IntellijProjectLibrarySources {
    @RequiresReadLock
    fun <Failure> sourceRoot(
        project: Project,
        binary: VirtualFile,
        sourceEntry: String,
        claimWork: () -> Refinement<Unit, Failure>,
    ): Refinement<VirtualFile, LibrarySourceRootFailure<Failure>> {
        when (val permit = claimWork()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return Refinement.Rejected(LibrarySourceRootFailure.WorkRejected(permit.failure))
        }
        val seen = mutableSetOf<String>()
        val matching = mutableListOf<VirtualFile>()
        for (entry in ProjectFileIndex.getInstance(project).getOrderEntriesForFile(binary)) {
            when (val permit = claimWork()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected ->
                    return Refinement.Rejected(LibrarySourceRootFailure.WorkRejected(permit.failure))
            }
            if (entry !is LibraryOrderEntry) continue
            when (val read = entry.collectSourceRoots(sourceEntry, seen, matching, claimWork)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return read
            }
        }
        return when (val root = matching.singleOrNull()) {
            null -> Refinement.Rejected(LibrarySourceRootFailure.Unavailable)
            else -> Refinement.Refined(root)
        }
    }
}

private fun <Failure> LibraryOrderEntry.collectSourceRoots(
    sourceEntry: String,
    seen: MutableSet<String>,
    matching: MutableList<VirtualFile>,
    claimWork: () -> Refinement<Unit, Failure>,
): Refinement<Unit, LibrarySourceRootFailure<Failure>> {
    for (root in getFiles(OrderRootType.SOURCES)) {
        when (val permit = claimWork()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return Refinement.Rejected(LibrarySourceRootFailure.WorkRejected(permit.failure))
        }
        if (seen.add(root.url) && root.findFileByRelativePath(sourceEntry) != null) {
            if (matching.isNotEmpty()) return Refinement.Rejected(LibrarySourceRootFailure.Ambiguous)
            matching.add(root)
        }
    }
    return Refinement.Refined(Unit)
}
