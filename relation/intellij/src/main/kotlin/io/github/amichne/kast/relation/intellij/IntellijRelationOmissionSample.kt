package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiUtilCore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOmissionSample

internal fun IntellijK2RelationProjection.omissionSample(
    element: PsiElement,
    range: com.intellij.openapi.util.TextRange,
): RelationOmissionSample {
    val file = PsiUtilCore.getVirtualFile(element) ?: return RelationOmissionSample.Unavailable
    val detached = detach(file) as? IntellijDetachedRelationFile.Found ?: return RelationOmissionSample.Unavailable
    return when (
        val occurrence =
            RelationOccurrence.fromBoundary(
                detached.identity,
                element.textRange.startOffset + range.startOffset,
                element.textRange.startOffset + range.endOffset,
            )
    ) {
        is Refinement.Refined -> RelationOmissionSample.Located(occurrence.value)
        is Refinement.Rejected -> RelationOmissionSample.Unavailable
    }
}
