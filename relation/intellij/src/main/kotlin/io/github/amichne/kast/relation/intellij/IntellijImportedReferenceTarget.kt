package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationConfirmedReferenceTarget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtImportDirective

internal fun KtReference.isImportName(): Boolean =
    PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java, false) != null

/**
 * An import names every same-named declaration it brings into scope, so K2 resolves it to all of them. The import
 * references the subject when one candidate is the subject. It is different only when every candidate is a source-less
 * symbol or another projected declaration; any other candidate keeps the occurrence unresolved.
 */
internal fun IntellijK2RelationProjection.confirmImportedTarget(
    reference: KtReference,
    subject: RelationEndpoint,
): IntellijReferenceTargetResult {
    val candidates = observation.observedAnalyze(reference.element) { reference.resolveToSymbols().map { it.psi } }
    if (candidates.isEmpty()) return IntellijReferenceTargetResult.Unresolved
    var unresolved = false
    for (candidate in candidates) {
        when (candidate) {
            null -> Unit
            is PsiNamedElement ->
                when (val result = confirmDeclaration(candidate, subject)) {
                    is IntellijReferenceTargetResult.Confirmed -> return result
                    IntellijReferenceTargetResult.Different -> Unit
                    IntellijReferenceTargetResult.Unresolved -> unresolved = true
                }
            else -> unresolved = true
        }
    }
    return if (unresolved) IntellijReferenceTargetResult.Unresolved else IntellijReferenceTargetResult.Different
}

internal fun IntellijK2RelationProjection.confirmDeclaration(
    declaration: PsiNamedElement,
    subject: RelationEndpoint,
): IntellijReferenceTargetResult {
    val evidence =
        when (val result = project(declaration)) {
            is IntellijRelationDeclarationProjection.Projected -> result.evidence
            IntellijRelationDeclarationProjection.Unsupported -> return IntellijReferenceTargetResult.Unresolved
        }
    return when (val proof = RelationConfirmedReferenceTarget.fromCompiler(subject, evidence)) {
        is Refinement.Refined -> IntellijReferenceTargetResult.Confirmed(proof.value)
        is Refinement.Rejected -> IntellijReferenceTargetResult.Different
    }
}
