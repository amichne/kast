package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiUtilCore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest

/** Named graph facts preserve endpoint, occurrence, provenance, and callback admission together. */
internal class IntellijRelationFactEmitter(
    private val request: RelationRequest,
    private val projection: IntellijK2RelationProjection,
    private val collector: IntellijRelationCollector,
    private val provenance: (VirtualFile) -> OccurrenceProvenance,
    private val omit: (RelationLimitation, PsiElement, TextRange) -> Boolean,
) {
    fun emit(
        related: PsiNamedElement,
        occurrenceElement: PsiElement,
        relativeRange: com.intellij.openapi.util.TextRange,
    ): Boolean =
        when (val result = projection.project(related)) {
            is IntellijRelationDeclarationProjection.Projected -> emit(result, occurrenceElement, relativeRange)
            IntellijRelationDeclarationProjection.Unsupported ->
                omit(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
        }

    fun emit(
        related: IntellijRelationDeclarationProjection.Projected,
        occurrenceElement: PsiElement,
        relativeRange: com.intellij.openapi.util.TextRange,
        callback: io.github.amichne.kast.relation.contract.RelationCallbackObservation? = null,
    ): Boolean {
        val endpoint =
            when (
                val resolved =
                    RelationEndpoint.resolve(
                        request.subject.lease,
                        request.searchScope,
                        related.evidence,
                        request.searchConstraints,
                    )
            ) {
                is Refinement.Refined -> resolved.value
                is Refinement.Rejected ->
                    return omit(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
            }
        val occurrenceFile =
            PsiUtilCore.getVirtualFile(occurrenceElement)
                ?: return omit(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
        val occurrence =
            when (val result = detachOccurrence(occurrenceFile, occurrenceElement, relativeRange)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected ->
                    return omit(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
            }
        val provenance =
            when (val result = provenance(occurrenceFile)) {
                is OccurrenceProvenance.Found -> result.provenance
                OccurrenceProvenance.Unsupported ->
                    return omit(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
            }
        val fact =
            when (val result = fact(endpoint, occurrence, provenance)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected ->
                    return omit(RelationLimitation.UNSUPPORTED_ITEM, occurrenceElement, relativeRange)
            }
        return collector.accept(fact, callback)
    }

    private fun fact(
        endpoint: RelationEndpoint.Resolved,
        occurrence: RelationOccurrence,
        provenance: RelationProvenance,
    ) =
        if (request.meaning == RelationMeaning.Callees)
            RelationFact.create(request, request.subject, endpoint, occurrence, provenance)
        else RelationFact.create(request, endpoint, request.subject, occurrence, provenance)

    private fun detachOccurrence(
        file: VirtualFile,
        element: PsiElement,
        relativeRange: TextRange,
    ): Refinement<RelationOccurrence, RelationLimitation> {
        val detached =
            when (val result = projection.detach(file)) {
                is IntellijDetachedRelationFile.Found -> result.identity
                IntellijDetachedRelationFile.Unsupported ->
                    return Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
            }
        return when (
            val result =
                RelationOccurrence.fromBoundary(
                    detached,
                    element.textRange.startOffset + relativeRange.startOffset,
                    element.textRange.startOffset + relativeRange.endOffset,
                )
        ) {
            is Refinement.Refined -> result
            is Refinement.Rejected -> Refinement.Rejected(RelationLimitation.UNSUPPORTED_ITEM)
        }
    }
}
