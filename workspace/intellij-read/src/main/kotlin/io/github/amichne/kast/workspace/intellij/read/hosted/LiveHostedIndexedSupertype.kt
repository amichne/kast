package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel
import org.jetbrains.kotlin.idea.stubindex.KotlinFullClassNameIndex
import org.jetbrains.kotlin.psi.KtClassOrObject
import java.nio.file.Path

/** Index discovery and K2 proof share the original admitted read and its publication checks. */
internal fun readHostedIndexedSupertype(
    project: Project,
    selection: HostedQualifiedClassSelection,
    model: DetachedIdeWorkspaceModel,
): HostedSemanticRead<HostedInheritorEvidence> {
    when (val saved = checkSavedDocuments(project)) {
        SavedDocuments.Clean -> Unit
        is SavedDocuments.Rejected -> return HostedSemanticRead.Rejected(saved.failure)
    }
    val candidates = HostedIndexCandidates<KtClassOrObject>()
    val complete = KotlinFullClassNameIndex.processElements(selection.signature.qualifiedIdentity.value, project, HostedIndexScope(project, model)) {
        ProgressManager.checkCanceled()
        candidates.accept(it) == HostedIndexCollection.CONTINUE
    }
    val found = when (val result = candidates.finish()) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return HostedSemanticRead.Rejected(result.failure)
    }
    if (!complete) return HostedSemanticRead.Rejected(HostedQueryFailure.READ_PREEMPTED)
    // Leave the index callback before touching declaration PSI or invoking K2.
    val unique = when (val result = HostedUniqueDeclaration.select(found)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return HostedSemanticRead.Rejected(result.failure)
    }
    val declaration = unique.value
    if (!declaration.isValid) return HostedSemanticRead.Rejected(HostedQueryFailure.CONTENT_MOVED)
    val file = declaration.containingFile.virtualFile
        ?: return HostedSemanticRead.Rejected(HostedQueryFailure.FILE_UNAVAILABLE)
    val name = declaration.nameIdentifier
        ?: return HostedSemanticRead.Rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
    val root = Path.of(selection.root.value)
    val path = Path.of(file.path)
    if (!path.startsWith(root)) return HostedSemanticRead.Rejected(HostedQueryFailure.OUTSIDE_SCOPE)
    val located = when (val result = HostedKotlinSelection.parse(selection.root, root.relativize(path).toString(), name.textOffset)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return HostedSemanticRead.Rejected(result.failure)
    }
    return when (val result = readHostedKotlin(project, located, model)) {
        is HostedSemanticRead.Rejected -> result
        is HostedSemanticRead.Resolved -> when (val verified = selection.verify(result.evidence.inheritor.symbol.signature)) {
            is Refinement.Refined -> result
            is Refinement.Rejected -> HostedSemanticRead.Rejected(verified.failure)
        }
    }
}
