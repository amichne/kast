package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiMethodCallExpression
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument

internal enum class NativeValueSiteRestorationFailure {
    ANCHOR_UNAVAILABLE,
    ROLE_SHAPE_UNSUPPORTED,
}

/** PSI shape refinement only. Existing native role and K2 binding checks establish semantic authority afterward. */
internal fun PsiElement.restoreValueSiteElement(
    range: ExactDeclarationTextRange,
    role: ValueSiteRoleClaim,
    observation: IntellijReadObservation,
): Refinement<PsiElement, NativeValueSiteRestorationFailure> {
    observation.phase(IntellijReadPhase.VALUE_SITE_RESTORATION)
    observation.count(IntellijReadCounter.VALUE_SITE_RESTORATIONS)
    val element = exactRetainedValueElement(range)
    if (element == null) {
        observation.count(IntellijReadCounter.VALUE_SITE_ANCHORS_UNAVAILABLE)
        return Refinement.Rejected(NativeValueSiteRestorationFailure.ANCHOR_UNAVAILABLE)
    }
    val matches =
        when (element) {
            is KtExpression -> element.matchesRoleShape(role)
            is PsiMethodCallExpression -> role is ValueSiteRoleClaim.Argument && element.parent is PsiExpressionList
            else -> false
        }
    if (!matches) {
        observation.count(IntellijReadCounter.VALUE_SITE_SHAPES_REJECTED)
        return Refinement.Rejected(NativeValueSiteRestorationFailure.ROLE_SHAPE_UNSUPPORTED)
    }
    observation.count(IntellijReadCounter.VALUE_SITE_SHAPES_RESTORED)
    return Refinement.Refined(element)
}

private fun KtExpression.matchesRoleShape(role: ValueSiteRoleClaim): Boolean =
    when (role) {
        is ValueSiteRoleClaim.LoopResult -> this is KtForExpression
        ValueSiteRoleClaim.LocalBinding ->
            this is KtProperty ||
                nativeLocalBindingMutability() is Refinement.Refined ||
                this is org.jetbrains.kotlin.psi.KtBinaryExpression &&
                    operationToken == org.jetbrains.kotlin.lexer.KtTokens.EQ
        ValueSiteRoleClaim.PropertyAssignment -> this is KtProperty
        is ValueSiteRoleClaim.Argument ->
            (parent as? KtValueArgument)?.getArgumentExpression() === this ||
                (parent as? org.jetbrains.kotlin.psi.KtBinaryExpression)?.right === this
        is ValueSiteRoleClaim.Receiver ->
            this is KtForExpression ||
                (parent as? KtQualifiedExpression)?.receiverExpression === this ||
                (parent as? org.jetbrains.kotlin.psi.KtBinaryExpression)?.left === this ||
                (this is KtDestructuringDeclarationEntry && isNativeComponent())
        ValueSiteRoleClaim.ExpressionResult ->
            this !is KtNamedDeclaration || (this is KtDestructuringDeclarationEntry && isNativeComponent())
        ValueSiteRoleClaim.LocalRead,
        ValueSiteRoleClaim.Return -> this !is KtNamedDeclaration
    }
