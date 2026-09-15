package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeEnumerator
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileClassification
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileIndexClassifier
import java.nio.file.Path

/** Uses only the existing VFS and imported source ownership; never refreshes or imports. */
internal fun projectBoundDiagnosticEnumeration(
    project: Project,
    authority: io.github.amichne.kast.workspace.contract.SemanticReadAuthority,
    limits: ReadLimits,
    admitsFile: (Path) -> Boolean,
    admitsDirectory: (Path) -> Boolean,
): DiagnosticScopeEnumerator = DiagnosticScopeEnumerator { request, budget ->
    val start = System.nanoTime()
    val allowance = DiagnosticEnumerationAllowance(budget) { (System.nanoTime() - start) / 1_000_000 }
    readAction {
        if (project.isDisposed || request.query.lease != authority)
            return@readAction DiagnosticEnumerationResult.Rejected(DiagnosticScopeResolutionFailure.WORKSPACE_NOT_READY)
        // All attempt-local state, including the probe and collector, is discarded on retry.
        enumerateDiagnosticTree(
            request,
            object : DiagnosticTreeProbe {
                override fun root(): DiagnosticTreeEntry =
                    observe(LocalFileSystem.getInstance().findFileByNioFile(request.query.path))

                override fun child(directory: Path, ordinal: Int): DiagnosticTreeEntry {
                    ProgressManager.checkCanceled()
                    val parent =
                        LocalFileSystem.getInstance().findFileByNioFile(directory)
                            ?: return DiagnosticTreeEntry.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE)
                    if (!parent.isValid || !parent.isDirectory || parent.canonicalPath?.let(Path::of) != directory) {
                        return DiagnosticTreeEntry.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE)
                    }
                    // The VFS owns this array; no child list or live object enters a checkpoint.
                    val children = parent.children
                    if (ordinal >= children.size) return DiagnosticTreeEntry.End
                    return observe(children[ordinal])
                }

                private fun observe(file: VirtualFile?): DiagnosticTreeEntry {
                    ProgressManager.checkCanceled()
                    if (file == null || !file.isValid)
                        return DiagnosticTreeEntry.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE)
                    val path = Path.of(file.path)
                    if (file.canonicalPath?.let(Path::of) != path || !path.startsWith(request.query.path)) {
                        return DiagnosticTreeEntry.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
                    }
                    if (file.isDirectory)
                        return if (admitsDirectory(path)) DiagnosticTreeEntry.Directory(path)
                        else DiagnosticTreeEntry.Ignored
                    if (file.extension !in setOf("kt", "kts") || !admitsFile(path)) return DiagnosticTreeEntry.Ignored
                    return when (IntellijProjectFileIndexClassifier.classify(project, file, limits = limits)) {
                        is IntellijProjectFileClassification.Source ->
                            when (
                                val scope =
                                    DiagnosticScope.fromCanonicalPaths(
                                        request.query.lease,
                                        listOf(path),
                                    )
                            ) {
                                is Refinement.Refined -> DiagnosticTreeEntry.File(scope.value.files.single())
                                is Refinement.Rejected ->
                                    DiagnosticTreeEntry.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
                            }
                        is IntellijProjectFileClassification.Rejected ->
                            DiagnosticTreeEntry.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE)
                        is IntellijProjectFileClassification.NotSource,
                        is IntellijProjectFileClassification.Library -> DiagnosticTreeEntry.Ignored
                    }
                }
            },
            allowance,
        )
    }
}
