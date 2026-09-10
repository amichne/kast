package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresReadLock

/** Native search-scope predicate; the workspace adapter retains sole file-index authority. */
object IntellijProjectSourceMembership {
    @RequiresReadLock
    fun contains(project: Project, file: VirtualFile): Boolean {
        val index = ProjectFileIndex.getInstance(project)
        return index.isInSourceContent(file) && !index.isExcluded(file)
    }
}
