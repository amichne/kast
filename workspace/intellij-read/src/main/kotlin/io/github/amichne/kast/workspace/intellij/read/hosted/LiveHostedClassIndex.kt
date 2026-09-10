@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootKind
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootProvenance
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.idea.stubindex.KotlinClassShortNameIndex
import org.jetbrains.kotlin.psi.KtClassOrObject
import java.nio.file.Path

/** IDEA maintains this index incrementally; Kast only reads its exact source scope in smart mode. */
internal fun readHostedClassIndex(project: Project, lookup: HostedClassLookup, model: DetachedIdeWorkspaceModel): HostedSemanticRead<HostedIndexedClasses> {
    when (val saved = checkSavedDocuments(project)) {
        SavedDocuments.Clean -> Unit
        is SavedDocuments.Rejected -> return HostedSemanticRead.Rejected(saved.failure)
    }
    val candidates = HostedIndexCandidates<KtClassOrObject>()
    // Finish the index callback before invoking K2, which may itself consult indexes.
    val complete = KotlinClassShortNameIndex.processElements(lookup.name.value, project, HostedIndexScope(project, model)) { declaration ->
        ProgressManager.checkCanceled()
        candidates.accept(declaration) == HostedIndexCollection.CONTINUE
    }
    val found = when (val collection = candidates.finish()) {
        is Refinement.Refined -> collection.value
        is Refinement.Rejected -> return HostedSemanticRead.Rejected(collection.failure)
    }
    if (!complete) return HostedSemanticRead.Rejected(HostedQueryFailure.READ_PREEMPTED)
    val detached = ArrayList<HostedCompilerDeclaration>(found.size)
    for (declaration in found) {
        ProgressManager.checkCanceled()
        if (!declaration.isValid || declaration.name != lookup.name.value) {
            return HostedSemanticRead.Rejected(HostedQueryFailure.CONTENT_MOVED)
        }
        val result = analyze(declaration) {
            val symbol = declaration.symbol as? KaNamedClassSymbol
                ?: return@analyze Refinement.Rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
            detachDeclaration(project, model, declaration, symbol)
        }
        when (result) {
            is Refinement.Refined -> detached += result.value
            is Refinement.Rejected -> return HostedSemanticRead.Rejected(result.failure)
        }
    }
    return HostedSemanticRead.Resolved(HostedIndexedClasses(lookup, detached.sortedWith(
        compareBy({ it.symbol.compilerIdentity.value }, { it.symbol.file.stableValue }, { it.symbol.range.startInclusive }),
    )))
}

private class HostedIndexScope(project: Project, model: DetachedIdeWorkspaceModel) : GlobalSearchScope(project) {
    private val root = Path.of(model.canonicalRoot.value)
    private val roots = model.modules.flatMap { module -> module.sourceRoots }.filter {
        it.kind in setOf(DetachedSourceRootKind.PRODUCTION, DetachedSourceRootKind.TEST) &&
            it.provenance == DetachedSourceRootProvenance.AUTHORED
    }.map { root.resolve(it.location.value) }

    override fun contains(file: VirtualFile): Boolean = file.isValid && file.fileSystem.protocol == "file" &&
        roots.any { Path.of(file.path).startsWith(it) }
    override fun isSearchInModuleContent(module: Module): Boolean = true
    override fun isSearchInLibraries(): Boolean = false
}
