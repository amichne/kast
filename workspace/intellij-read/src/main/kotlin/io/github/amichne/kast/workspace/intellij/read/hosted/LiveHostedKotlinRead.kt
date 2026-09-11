@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReadLimitParameter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootKind
import io.github.amichne.kast.workspace.intellij.read.DetachedSourceRootProvenance
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtUserType
import java.nio.file.Path

internal const val HOSTED_MAX_FILE_CHARACTERS = 262_144

/** Called only by the admitted-project cancellable read, with no transport or persistence I/O. */
internal fun readHostedKotlin(
    project: Project,
    selection: HostedKotlinSelection,
    model: DetachedIdeWorkspaceModel,
    limits: ReadLimits = ReadLimits.Default,
    ): HostedSemanticRead<HostedInheritorEvidence> {
    when (val saved = checkSavedDocuments(project)) {
        SavedDocuments.Clean -> Unit
        is SavedDocuments.Rejected -> return HostedSemanticRead.Rejected(saved.failure)
    }
    ProgressManager.checkCanceled()
    val virtualFile = LocalFileSystem.getInstance().findFileByPath(selection.file.path.value)
        ?: return rejected(HostedQueryFailure.FILE_UNAVAILABLE)
    if (virtualFile.length > limits[ReadLimitParameter.HOST_FILE_CHARACTERS].value * 4L) return rejected(HostedQueryFailure.FILE_TOO_LARGE)
    if (virtualFile.canonicalPath != virtualFile.path) return rejected(HostedQueryFailure.OUTSIDE_SCOPE)
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return rejected(HostedQueryFailure.FILE_UNAVAILABLE)
    if (document.textLength > limits[ReadLimitParameter.HOST_FILE_CHARACTERS].value) return rejected(HostedQueryFailure.FILE_TOO_LARGE)
    val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
        ?: return rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
    val element = file.findElementAt(selection.nameOffset.value)
        ?: return rejected(HostedQueryFailure.INVALID_SELECTION)
    val declaration = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java, false)
        ?: return rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
    if (declaration.nameIdentifier?.textRange?.contains(selection.nameOffset.value) != true) {
        return rejected(HostedQueryFailure.INVALID_SELECTION)
    }
    val entry = declaration.superTypeListEntries.singleOrNull()
        ?: return rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
    val reference = (entry.typeReference?.typeElement as? KtUserType)?.referenceExpression?.mainReference
        ?: return rejected(HostedQueryFailure.UNRESOLVED_SUPERTYPE)
    return analyze(declaration) {
        val subject = declaration.symbol as? KaNamedClassSymbol
            ?: return@analyze rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
        val parent = reference.resolveToSymbol() as? KaNamedClassSymbol
            ?: return@analyze rejected(HostedQueryFailure.UNRESOLVED_SUPERTYPE)
        if (subject.classId == null || parent.classId == null ||
            subject.superTypes.none { (it as? KaClassType)?.classId == parent.classId }
        ) return@analyze rejected(HostedQueryFailure.UNRESOLVED_SUPERTYPE)
        val parentDeclaration = parent.psi as? KtClassOrObject
            ?: return@analyze rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
        val child = when (val result = detachDeclaration(project, model, declaration, subject, limits = limits)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return@analyze rejected(result.failure)
        }
        val base = when (val result = detachDeclaration(project, model, parentDeclaration, parent, limits = limits)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return@analyze rejected(result.failure)
        }
        ProgressManager.checkCanceled()
        when (val saved = checkSavedDocuments(project)) {
            SavedDocuments.Clean -> Unit
            is SavedDocuments.Rejected -> return@analyze rejected(saved.failure)
        }
        HostedSemanticRead.Resolved(HostedInheritorEvidence(base, child))
    }
}

/** Conservatively rejects dirty dependencies anywhere in the host, including other projects. */
internal sealed interface SavedDocuments {
    data object Clean : SavedDocuments
    data class Rejected(val failure: HostedQueryFailure) : SavedDocuments
}

internal fun checkSavedDocuments(project: Project): SavedDocuments = when {
    FileDocumentManager.getInstance().unsavedDocuments.isNotEmpty() -> SavedDocuments.Rejected(HostedQueryFailure.DIRTY_DOCUMENTS)
    PsiDocumentManager.getInstance(project).hasUncommitedDocuments() -> SavedDocuments.Rejected(HostedQueryFailure.UNCOMMITTED_DOCUMENTS)
    else -> SavedDocuments.Clean
}

internal fun verifyHostedContent(project: Project, evidence: HostedInheritorEvidence): SavedDocuments {
    return verifyHostedDeclarations(project, listOf(evidence.supertype, evidence.inheritor))
}

internal fun verifyHostedDeclarations(project: Project, declarations: List<HostedCompilerDeclaration>): SavedDocuments {
    when (val saved = checkSavedDocuments(project)) {
        SavedDocuments.Clean -> Unit
        is SavedDocuments.Rejected -> return saved
    }
    for (declaration in declarations) {
        val file = LocalFileSystem.getInstance().findFileByPath(declaration.symbol.file.stableValue)
            ?: return SavedDocuments.Rejected(HostedQueryFailure.CONTENT_MOVED)
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
            ?: return SavedDocuments.Rejected(HostedQueryFailure.CONTENT_MOVED)
        if (file.modificationStamp != declaration.content.vfsStamp.value ||
            document.modificationStamp != declaration.content.documentStamp.value
        ) return SavedDocuments.Rejected(HostedQueryFailure.CONTENT_MOVED)
    }
    return SavedDocuments.Clean
}

internal fun detachDeclaration(
    project: Project,
    model: DetachedIdeWorkspaceModel,
    declaration: KtClassOrObject,
    symbol: KaNamedClassSymbol,
    limits: ReadLimits = ReadLimits.Default,
    ): Refinement<HostedCompilerDeclaration, HostedQueryFailure> {
    val virtualFile = declaration.containingFile.virtualFile
        ?: return Refinement.Rejected(HostedQueryFailure.FILE_UNAVAILABLE)
    if (virtualFile.canonicalPath != virtualFile.path) return Refinement.Rejected(HostedQueryFailure.OUTSIDE_SCOPE)
    val file = when (val result = SymbolDiscoveryFileIdentity.fromBoundary(
        model.canonicalRoot, Path.of(virtualFile.path), virtualFile.url,
    )) {
        is Refinement.Refined -> result.value as? SymbolDiscoveryFileIdentity.Workspace
            ?: return Refinement.Rejected(HostedQueryFailure.OUTSIDE_SCOPE)
        is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.OUTSIDE_SCOPE)
    }
    val scope = when (val admitted = HostedSourceScope.admit(model, file)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return admitted
    }
    if (virtualFile.length > limits[ReadLimitParameter.HOST_FILE_CHARACTERS].value * 4L) return Refinement.Rejected(HostedQueryFailure.FILE_TOO_LARGE)
    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return Refinement.Rejected(HostedQueryFailure.FILE_UNAVAILABLE)
    if (document.textLength > limits[ReadLimitParameter.HOST_FILE_CHARACTERS].value) return Refinement.Rejected(HostedQueryFailure.FILE_TOO_LARGE)
    if (FileDocumentManager.getInstance().isFileModified(virtualFile)) return Refinement.Rejected(HostedQueryFailure.DIRTY_DOCUMENTS)
    if (!PsiDocumentManager.getInstance(project).isCommitted(document)) return Refinement.Rejected(HostedQueryFailure.UNCOMMITTED_DOCUMENTS)
    val qualifiedName = symbol.classId?.asSingleFqName()?.asString()
        ?: return Refinement.Rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
    val signature = when (val result = CanonicalCompilerSignature.classLike(qualifiedName)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
    }
    val content = when (val revision = HostedContentRevision.observe(document.modificationStamp, virtualFile.modificationStamp)) {
        is Refinement.Refined -> revision.value
        is Refinement.Rejected -> return revision
    }
    return when (val evidence = CompilerGroundedSymbolEvidence.fromBoundary(
        file, declaration.textRange.startOffset, declaration.textRange.endOffset,
        declaration.name.orEmpty(), qualifiedName, CompilerSymbolKind.CLASSLIKE, signature,
    )) {
        is Refinement.Refined -> Refinement.Refined(HostedCompilerDeclaration(
            evidence.value, content, scope.module, scope.root,
        ))
        is Refinement.Rejected -> Refinement.Rejected(HostedQueryFailure.UNSUPPORTED_DECLARATION)
    }
}

private fun rejected(failure: HostedQueryFailure) = HostedSemanticRead.Rejected(failure)
