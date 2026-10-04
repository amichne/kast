package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** A qualified call's value is the whole selector expression, including its receiver and safe-call operator. */
internal fun KtCallElement.valueInvocationExpression(): PsiElement {
    val qualified = parent as? KtQualifiedExpression
    return if (qualified?.selectorExpression === this) qualified else this
}
