package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddressFailure
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtFunctionLiteral

private const val MAX_LOCAL_LEXICAL_ANCESTRY_WORK = 256
internal const val DEFAULT_LOCAL_OWNER_DEPTH = 32

/** Structural anchors; the caller must independently prove the compiler owner. */
internal fun localDeclarationLexicalOwners(
    declaration: PsiElement,
    owner: PsiElement,
    workAllowance: WorkAllowance = defaultLocalCompilerTypeAllowance(),
): Refinement<List<ExactDeclarationTextRange>, LocalDeclarationProjectionFailure> {
    val lexical = mutableListOf<ExactDeclarationTextRange>()
    var current = declaration.parent
    var examined = 0L
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PsiElement, Boolean>())
    while (current !== owner) {
        ProgressManager.checkCanceled()
        if (current == null || current is PsiFile)
            return Refinement.Rejected(LocalDeclarationProjectionFailure.LexicalAncestryUnavailable)
        if (workAllowance == WorkAllowance.Unlimited && !seen.add(current))
            return Refinement.Rejected(LocalDeclarationProjectionFailure.LexicalAncestryUnavailable)
        if (!workAllowance.permitsLexicalAncestry(++examined))
            return Refinement.Rejected(LocalDeclarationProjectionFailure.WorkLimitReached)
        when (val admitted = appendLocalLexicalOwner(current, lexical, workAllowance)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
        current = current.parent
    }
    return Refinement.Refined(java.util.List.copyOf(lexical.asReversed()))
}

private fun WorkAllowance.permitsLexicalAncestry(examined: Long): Boolean =
    permits(examined) &&
        when (this) {
            is WorkAllowance.Bounded -> examined <= MAX_LOCAL_LEXICAL_ANCESTRY_WORK
            WorkAllowance.Unlimited -> true
        }

private fun PsiElement.localLexicalAnchor(): Refinement<ExactDeclarationTextRange, LocalDeclarationProjectionFailure> {
    val anchor = textRange ?: return Refinement.Rejected(LocalDeclarationProjectionFailure.LexicalAncestryUnavailable)
    return when (val parsed = ExactDeclarationTextRange.parse(anchor.startOffset, anchor.endOffset)) {
        is Refinement.Refined -> parsed
        is Refinement.Rejected ->
            Refinement.Rejected(
                LocalDeclarationProjectionFailure.InvalidAddress(LocalDeclarationAddressFailure.INVALID_LEXICAL_OWNER)
            )
    }
}

private fun appendLocalLexicalOwner(
    current: PsiElement,
    lexical: MutableList<ExactDeclarationTextRange>,
    workAllowance: WorkAllowance,
): Refinement<Unit, LocalDeclarationProjectionFailure> {
    if (current !is KtBlockExpression && current !is KtFunctionLiteral && current !is KtCatchClause)
        return Refinement.Refined(Unit)
    val detached =
        when (val anchor = current.localLexicalAnchor()) {
            is Refinement.Refined -> anchor.value
            is Refinement.Rejected -> return anchor
        }
    if (lexical.lastOrNull() != detached) lexical.add(detached)
    return if (workAllowance is WorkAllowance.Bounded && lexical.size > DEFAULT_LOCAL_OWNER_DEPTH)
        Refinement.Rejected(LocalDeclarationProjectionFailure.OwnerDepthExceeded)
    else Refinement.Refined(Unit)
}
