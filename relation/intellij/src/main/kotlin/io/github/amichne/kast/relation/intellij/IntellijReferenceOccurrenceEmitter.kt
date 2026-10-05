package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiUtilCore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOwnershipUnavailableCause
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest

/** Compiler target proof survives occurrence projection independently of declaration ownership. */
internal class IntellijReferenceOccurrenceEmitter(
    private val request: RelationRequest,
    private val projection: IntellijK2RelationProjection,
    private val collector: IntellijRelationCollector,
    private val provenance: (VirtualFile) -> OccurrenceProvenance,
    private val omit: (RelationLimitation, PsiElement, TextRange) -> Boolean,
) {
    fun confirm(reference: PsiReference, result: IntellijReferenceTargetResult): Boolean =
        when (result) {
            is IntellijReferenceTargetResult.Confirmed -> emitReference(reference, result.target)
            IntellijReferenceTargetResult.Different -> collector.dismissProviderItem()
            IntellijReferenceTargetResult.Unresolved ->
                omit(RelationLimitation.UNRESOLVED_TARGET, reference.element, reference.rangeInElement)
        }

    private fun emitReference(
        reference: PsiReference,
        target: io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget,
    ): Boolean {
        val element = reference.element
        val nativeFile =
            PsiUtilCore.getVirtualFile(element)
                ?: return omit(RelationLimitation.UNSUPPORTED_ITEM, element, reference.rangeInElement)
        val detachedFile =
            when (val detached = projection.detach(nativeFile)) {
                is IntellijDetachedRelationFile.Found -> detached.identity
                IntellijDetachedRelationFile.Unsupported ->
                    return omit(RelationLimitation.UNSUPPORTED_ITEM, element, reference.rangeInElement)
            }
        val occurrence =
            when (
                val detached =
                    RelationOccurrence.fromBoundary(
                        detachedFile,
                        element.textRange.startOffset + reference.rangeInElement.startOffset,
                        element.textRange.startOffset + reference.rangeInElement.endOffset,
                    )
            ) {
                is Refinement.Refined -> detached.value
                is Refinement.Rejected ->
                    return omit(RelationLimitation.UNSUPPORTED_ITEM, element, reference.rangeInElement)
            }
        val provenance =
            when (val classified = provenance(nativeFile)) {
                is OccurrenceProvenance.Found -> classified.provenance
                OccurrenceProvenance.Unsupported ->
                    return omit(RelationLimitation.UNSUPPORTED_ITEM, element, reference.rangeInElement)
            }
        val context = element.referenceContext()
        val ownership = ownership(element, context)
        val confirmed =
            when (
                val result =
                    io.github.amichne.kast.relation.contract.RelationReferenceOccurrence.confirmed(
                        request,
                        target,
                        occurrence,
                        context,
                        ownership,
                        provenance,
                    )
            ) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected ->
                    return omit(RelationLimitation.UNSUPPORTED_ITEM, element, reference.rangeInElement)
            }
        return collector.acceptReference(confirmed)
    }

    private fun ownership(element: PsiElement, context: RelationReferenceContext): RelationReferenceOwnership {
        return if (context.isFileScoped()) {
            io.github.amichne.kast.relation.contract.RelationReferenceOwnership.FileScoped(context)
        } else
            when (val owner = element.nearestSupportedDeclaration(projection)) {
                is SupportedContainingDeclaration.Found ->
                    when (
                        val endpoint =
                            RelationEndpoint.resolve(
                                request.subject.lease,
                                request.searchScope,
                                owner.projection.evidence,
                                request.searchConstraints,
                            )
                    ) {
                        is Refinement.Refined ->
                            io.github.amichne.kast.relation.contract.RelationReferenceOwnership.DeclarationOwned(
                                endpoint.value
                            )
                        is Refinement.Rejected ->
                            io.github.amichne.kast.relation.contract.RelationReferenceOwnership.Unavailable(
                                io.github.amichne.kast.relation.contract.RelationOwnershipUnavailableCause
                                    .UNSUPPORTED_DECLARATION
                            )
                    }
                SupportedContainingDeclaration.Unresolved ->
                    io.github.amichne.kast.relation.contract.RelationReferenceOwnership.Unavailable(
                        io.github.amichne.kast.relation.contract.RelationOwnershipUnavailableCause
                            .UNRESOLVED_DECLARATION
                    )
                SupportedContainingDeclaration.Unsupported,
                is SupportedContainingDeclaration.Excluded ->
                    io.github.amichne.kast.relation.contract.RelationReferenceOwnership.Unavailable(
                        io.github.amichne.kast.relation.contract.RelationOwnershipUnavailableCause
                            .UNSUPPORTED_DECLARATION
                    )
            }
    }
}
