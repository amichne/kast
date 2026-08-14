package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.util.PsiUtilCore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationEvidence
import io.github.amichne.kast.symbol.contract.ExactRelationEndpoint
import io.github.amichne.kast.symbol.contract.NativeRelationFact
import io.github.amichne.kast.symbol.contract.NativeRelationOccurrence
import io.github.amichne.kast.symbol.contract.NativeRelationRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

private sealed interface IntellijDetachedRelationFile {
    data class Found(
        val identity: SymbolDiscoveryFileIdentity,
    ) : IntellijDetachedRelationFile

    data object Unsupported : IntellijDetachedRelationFile
}

internal object IntellijPsiRelationFactProjector : IntellijRelationFactProjector {
    /**
     * Proof transition:
     * NativeRelationRequest + IntellijPsiRelationEvent to
     * Refinement<NativeRelationFact, IntellijRelationProjectionFailure>.
     *
     * Establishes exact related-declaration evidence and an exact occurrence range bound to the
     * request selector's root, current epoch, scope, and relation family. Projection failures are
     * closed as unsupported items; all live IntelliJ values remain inside this call.
     */
    override fun project(
        request: NativeRelationRequest,
        event: IntellijNativeRelationEvent,
    ): Refinement<NativeRelationFact, IntellijRelationProjectionFailure> {
        val psiEvent = event as? IntellijPsiRelationEvent
                       ?: return Refinement.Rejected(IntellijRelationProjectionFailure.UNSUPPORTED_ITEM)
        val relatedFile = PsiUtilCore.getVirtualFile(psiEvent.related)
                          ?: return Refinement.Rejected(IntellijRelationProjectionFailure.UNSUPPORTED_ITEM)
        val detachedRelatedFile = when (val detached = relatedFile.detach(request)) {
            is IntellijDetachedRelationFile.Found -> detached.identity
            IntellijDetachedRelationFile.Unsupported ->
                return Refinement.Rejected(IntellijRelationProjectionFailure.UNSUPPORTED_ITEM)
        }
        val evidence = when (
            val refined = ExactDeclarationEvidence.fromBoundary(
                file = detachedRelatedFile,
                rawStartInclusive = psiEvent.related.textRange.startOffset,
                rawEndExclusive = psiEvent.related.textRange.endOffset,
                rawName = psiEvent.related.name.orEmpty(),
                rawQualifiedIdentity =
                    (psiEvent.related as? PsiQualifiedNamedElement)?.qualifiedName,
                rawRuntimeType = psiEvent.related.javaClass.name,
            )
        ) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected ->
                return Refinement.Rejected(IntellijRelationProjectionFailure.UNSUPPORTED_ITEM)
        }
        val occurrenceFile = when (val detached = psiEvent.occurrenceFile.detach(request)) {
            is IntellijDetachedRelationFile.Found -> detached.identity
            IntellijDetachedRelationFile.Unsupported ->
                return Refinement.Rejected(IntellijRelationProjectionFailure.UNSUPPORTED_ITEM)
        }
        val occurrence = when (
            val refined = NativeRelationOccurrence.fromBoundary(
                file = occurrenceFile,
                rawStartInclusive = psiEvent.occurrenceStartInclusive,
                rawEndExclusive = psiEvent.occurrenceEndExclusive,
            )
        ) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected ->
                return Refinement.Rejected(IntellijRelationProjectionFailure.UNSUPPORTED_ITEM)
        }
        return when (
            val fact = NativeRelationFact.create(
                subject = request.selector,
                family = request.family,
                related = ExactRelationEndpoint.bind(request.selector, evidence),
                occurrence = occurrence,
            )
        ) {
            is Refinement.Refined -> fact
            is Refinement.Rejected ->
                Refinement.Rejected(IntellijRelationProjectionFailure.UNSUPPORTED_ITEM)
        }
    }

    /**
     * Proof transition: `VirtualFile + NativeRelationRequest -> IntellijDetachedRelationFile`.
     *
     * Establishes a detached file identity rooted in the selector's canonical workspace. Relative,
     * unavailable, or non-refinable native paths are represented by the closed unsupported state.
     */
    private fun VirtualFile.detach(
        request: NativeRelationRequest,
    ): IntellijDetachedRelationFile {
        val path = when (val classified = nativePath(this)) {
            is IntellijVirtualFilePath.Absolute -> classified.value
            IntellijVirtualFilePath.Relative,
            IntellijVirtualFilePath.Unavailable,
                -> null
        }
        return when (
            val refined = SymbolDiscoveryFileIdentity.fromBoundary(
                workspaceRoot = request.selector.lease.workspaceRoot,
                nativePath = path,
                virtualFileUrl = url,
            )
        ) {
            is Refinement.Refined -> IntellijDetachedRelationFile.Found(refined.value)
            is Refinement.Rejected -> IntellijDetachedRelationFile.Unsupported
        }
    }
}
