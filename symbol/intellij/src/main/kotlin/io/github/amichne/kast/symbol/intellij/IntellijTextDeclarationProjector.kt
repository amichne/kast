package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.util.PsiUtilCore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateFailure
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolTextMatch
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Request-local containment/projection stage; the existing exact resolver owns compiler identity. */
internal class IntellijTextDeclarationProjector(
    private val request: SymbolDiscoveryRequest,
    private val scope: CompiledIntellijSearchScope,
    private val word: SymbolDiscoveryWord,
    private val collector: SupplementalCollector,
    private val observation: IntellijReadObservation,
) {
    private val policies =
        IntellijDiscoveryItemPolicies(
            IntellijPsiDiscoveryItemFile,
            IntellijPsiDiscoveryCandidateProjector,
            AdmitEveryIntellijDiscoveryItem,
            IntellijPsiDiscoveryItemCompilerKind,
            IntellijPsiDiscoveryItemPackage,
        )

    /** Native callbacks admit cheap scope/kind facts; package PSI follows complete collection. */
    fun admit(element: PsiElement, offset: Int): IntellijTextOccurrenceAdmission {
        val file = element.containingFile ?: return IntellijTextOccurrenceAdmission.UNSUPPORTED
        val virtual = PsiUtilCore.getVirtualFile(file) ?: return IntellijTextOccurrenceAdmission.UNSUPPORTED
        if (!scope.nativeScope.contains(virtual)) return IntellijTextOccurrenceAdmission.FILTERED
        val owner =
            when (val containing = containingOwner(element, offset)) {
                is IntellijTextContainingOwner.Found -> containing.owner
                IntellijTextContainingOwner.Unsupported -> return IntellijTextOccurrenceAdmission.UNSUPPORTED
            }
        when (IntellijPsiDiscoveryItemCompilerKind.classify(owner)) {
            is IntellijDiscoveryItemCompilerKindResult.Found -> Unit
            IntellijDiscoveryItemCompilerKindResult.EnumEntry,
            IntellijDiscoveryItemCompilerKindResult.Unsupported -> return IntellijTextOccurrenceAdmission.UNSUPPORTED
        }
        return when (request.constraints.admit(owner, virtual.path, scope, policies, inspectPackage = false)) {
            IntellijDiscoveryItemAdmission.ADMITTED -> IntellijTextOccurrenceAdmission.ADMITTED
            IntellijDiscoveryItemAdmission.FILTERED -> IntellijTextOccurrenceAdmission.FILTERED
            IntellijDiscoveryItemAdmission.UNSUPPORTED -> IntellijTextOccurrenceAdmission.UNSUPPORTED
        }
    }

    fun project(element: PsiElement, offset: Int): Boolean {
        val owner =
            when (val prepared = prepareOwner(element, offset)) {
                is PreparedTextOwner.Found -> prepared
                PreparedTextOwner.Filtered -> return true
                PreparedTextOwner.Unsupported -> return unsupported()
            }
        if (collector.containsOwner(owner.candidate)) return true
        val evidence =
            when (val projected = projectEvidence(owner)) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return unsupported()
            }
        observation.count(IntellijReadCounter.CANDIDATES_PROJECTED)
        return collector.accept(owner.candidate.withTextMatch(evidence))
    }

    private fun prepareOwner(element: PsiElement, offset: Int): PreparedTextOwner {
        val containing =
            when (val result = containingOwner(element, offset)) {
                is IntellijTextContainingOwner.Found -> result
                IntellijTextContainingOwner.Unsupported -> return PreparedTextOwner.Unsupported
            }
        val owner = containing.owner
        val file = owner.containingFile ?: return PreparedTextOwner.Unsupported
        val virtual = PsiUtilCore.getVirtualFile(file) ?: return PreparedTextOwner.Unsupported
        when (request.constraints.admit(owner, virtual.path, scope, policies)) {
            IntellijDiscoveryItemAdmission.ADMITTED -> Unit
            IntellijDiscoveryItemAdmission.FILTERED -> return PreparedTextOwner.Filtered
            IntellijDiscoveryItemAdmission.UNSUPPORTED -> return PreparedTextOwner.Unsupported
        }
        val candidate =
            when (val projected = detachOwner(owner, virtual)) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return PreparedTextOwner.Unsupported
            }
        return PreparedTextOwner.Found(candidate, file, owner.textRange, containing.offset)
    }

    private fun detachOwner(
        owner: KtNamedDeclaration,
        file: VirtualFile,
    ): Refinement<SymbolDiscoveryCandidate, SymbolDiscoveryCandidateFailure> {
        val kind =
            when (val classified = IntellijPsiDiscoveryItemCompilerKind.classify(owner)) {
                is IntellijDiscoveryItemCompilerKindResult.Found ->
                    if (classified.kind == CompilerSymbolKind.CLASSLIKE) SymbolDiscoveryKind.CLASS
                    else SymbolDiscoveryKind.SYMBOL
                IntellijDiscoveryItemCompilerKindResult.EnumEntry,
                IntellijDiscoveryItemCompilerKindResult.Unsupported ->
                    return Refinement.Rejected(SymbolDiscoveryCandidateFailure.TARGET_KIND_MISMATCH)
            }
        val path =
            when (val native = nativePath(file)) {
                is IntellijVirtualFilePath.Absolute -> native.value
                IntellijVirtualFilePath.Relative,
                IntellijVirtualFilePath.Unavailable ->
                    return Refinement.Rejected(SymbolDiscoveryCandidateFailure.INVALID_FILE_LOCATION)
            }
        return SymbolDiscoveryCandidate.fromBoundary(
            kind,
            owner.name.orEmpty(),
            request.scope.lease,
            path,
            file.url,
            owner.textRange.startOffset,
        )
    }

    private fun projectEvidence(
        owner: PreparedTextOwner.Found
    ): Refinement<SymbolTextMatch, SymbolDiscoveryQualification> {
        val file =
            when (val identity = owner.candidate.location.file) {
                is SymbolDiscoveryFileIdentity.Workspace -> identity.path
                is SymbolDiscoveryFileIdentity.External ->
                    return Refinement.Rejected(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
            }
        val document =
            owner.file.viewProvider.document
                ?: return Refinement.Rejected(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
        val context =
            when (val projected = projectIndexedWordContext(document, owner.offset, word)) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return projected
            }
        return when (
            val projected =
                SymbolTextMatch.fromBoundary(
                    word,
                    request.scope.lease,
                    file,
                    context.range.startInclusive.value,
                    context.range.endExclusive.value,
                    owner.range.startOffset,
                    owner.range.endOffset,
                    context.text,
                    context.contextRange.startInclusive.value,
                    context.line,
                )
        ) {
            is Refinement.Refined -> projected
            is Refinement.Rejected -> Refinement.Rejected(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
        }
    }

    private fun unsupported(): Boolean {
        collector.qualify(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
        return true
    }
}

private sealed interface PreparedTextOwner {
    data class Found(
        val candidate: SymbolDiscoveryCandidate,
        val file: PsiFile,
        val range: TextRange,
        val offset: SymbolDiscoverySourceOffset,
    ) : PreparedTextOwner

    data object Filtered : PreparedTextOwner

    data object Unsupported : PreparedTextOwner
}

/** Named containment and its admitted UTF16 offset stay request-local. */
private sealed interface IntellijTextContainingOwner {
    data class Found(val owner: KtNamedDeclaration, val offset: SymbolDiscoverySourceOffset) :
        IntellijTextContainingOwner

    data object Unsupported : IntellijTextContainingOwner
}

/** Unsupported owners are never skipped to invent an outer owner. Files and directories terminate traversal. */
private fun containingOwner(element: PsiElement, offset: Int): IntellijTextContainingOwner {
    val file = element.containingFile ?: return IntellijTextContainingOwner.Unsupported
    if (offset < 0) return IntellijTextContainingOwner.Unsupported
    val base = if (element is PsiFile) 0 else element.textRange.startOffset
    if (base > Int.MAX_VALUE - offset) return IntellijTextContainingOwner.Unsupported
    val absolute =
        when (val parsed = SymbolDiscoverySourceOffset.parse(base + offset)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return IntellijTextContainingOwner.Unsupported
        }
    var current: PsiElement? = file.findElementAt(absolute.value)
    while (current != null && current !is PsiFileSystemItem) {
        if (current is KtNamedDeclaration) return IntellijTextContainingOwner.Found(current, absolute)
        current = current.parent
    }
    return IntellijTextContainingOwner.Unsupported
}
