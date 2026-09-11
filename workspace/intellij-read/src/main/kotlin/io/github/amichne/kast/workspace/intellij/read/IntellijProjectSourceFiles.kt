package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReadLimitParameter
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import kotlinx.coroutines.CancellationException

/** The existing project-file-index owner alone expands a source scope. No filesystem walk. */
object IntellijProjectSourceFiles {
    @RequiresReadLock
    fun collect(
        project: Project,
        root: CanonicalWorkspaceRoot,
        scope: Path,
        budget: ResourceBudget,
        limits: ReadLimits = ReadLimits.Default,
    ): Refinement<List<Path>, ProjectSourceFileFailure> {
        if (project.isDisposed) return Refinement.Rejected(ProjectSourceFileFailure.UNAVAILABLE)
        val workspace = Path.of(root.value)
        if (!scope.isAbsolute || scope.normalize() != scope || !scope.startsWith(workspace)) {
            return Refinement.Rejected(ProjectSourceFileFailure.INVALID_SCOPE)
        }
        return try {
            val start = System.nanoTime()
            val requested = LocalFileSystem.getInstance().findFileByNioFile(scope)
                ?: return Refinement.Rejected(ProjectSourceFileFailure.UNAVAILABLE)
            if (!requested.isValid || requested.canonicalPath?.let(Path::of) != scope) {
                return Refinement.Rejected(ProjectSourceFileFailure.INVALID_SCOPE)
            }
            val index = ProjectFileIndex.getInstance(project)
            val collector = BoundedSourceFileCollector(scope, budget)
            val visit = ContentIterator { file ->
                ProgressManager.checkCanceled()
                collector.accept(classify(project, file, limits = limits), System.nanoTime() - start)
            }
            if (requested.isDirectory) {
                index.iterateContentUnderDirectory(requested, visit)
            } else {
                visit.processFile(requested)
            }
            collector.finish()
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            Refinement.Rejected(ProjectSourceFileFailure.UNAVAILABLE)
        } catch (_: LinkageError) {
            Refinement.Rejected(ProjectSourceFileFailure.UNAVAILABLE)
        }
    }

    private fun classify(project: Project, file: VirtualFile, limits: ReadLimits = ReadLimits.Default): ProjectSourceEntry {
        if (!file.isValid) return ProjectSourceEntry.Rejected(ProjectSourceFileFailure.UNAVAILABLE)
        if (file.isDirectory || file.extension !in setOf("kt", "kts")) return ProjectSourceEntry.Ignored
        return when (IntellijProjectFileIndexClassifier.classify(project, file, limits = limits)) {
            is IntellijProjectFileClassification.Source -> {
                val path = Path.of(file.path)
                if (file.canonicalPath?.let(Path::of) == path) ProjectSourceEntry.Source(path)
                else ProjectSourceEntry.Rejected(ProjectSourceFileFailure.INVALID_SCOPE)
            }
            is IntellijProjectFileClassification.Rejected -> ProjectSourceEntry.Rejected(ProjectSourceFileFailure.UNAVAILABLE)
            is IntellijProjectFileClassification.NotSource,
            is IntellijProjectFileClassification.Library -> ProjectSourceEntry.Ignored
        }
    }
}
