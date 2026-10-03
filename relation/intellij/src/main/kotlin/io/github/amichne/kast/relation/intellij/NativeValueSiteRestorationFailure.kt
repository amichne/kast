package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
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
): Refinement<KtExpression, NativeValueSiteRestorationFailure> {
    observation.phase(IntellijReadPhase.VALUE_SITE_RESTORATION)
    observation.count(IntellijReadCounter.VALUE_SITE_RESTORATIONS)
    val element = exactValueElement(range)
    if (element == null) {
        observation.count(IntellijReadCounter.VALUE_SITE_ANCHORS_UNAVAILABLE)
        return Refinement.Rejected(NativeValueSiteRestorationFailure.ANCHOR_UNAVAILABLE)
    }
    if (!element.matchesRoleShape(role)) {
        observation.count(IntellijReadCounter.VALUE_SITE_SHAPES_REJECTED)
        return Refinement.Rejected(NativeValueSiteRestorationFailure.ROLE_SHAPE_UNSUPPORTED)
    }
    observation.count(IntellijReadCounter.VALUE_SITE_SHAPES_RESTORED)
    return Refinement.Refined(element)
}

private fun KtExpression.matchesRoleShape(role: ValueSiteRoleClaim): Boolean =
    when (role) {
        ValueSiteRoleClaim.LocalBinding,
        ValueSiteRoleClaim.PropertyAssignment -> this is KtProperty
        is ValueSiteRoleClaim.Argument -> (parent as? KtValueArgument)?.getArgumentExpression() === this
        ValueSiteRoleClaim.ExpressionResult,
        ValueSiteRoleClaim.LocalRead,
        ValueSiteRoleClaim.Return -> this !is KtNamedDeclaration
    }
