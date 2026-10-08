package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTryExpression

/** A returned value crosses every enclosing try boundary up to its already admitted executable owner. */
internal fun returnCrossesFinally(expression: KtReturnExpression, owner: PsiElement): Boolean {
    var current: PsiElement? = expression.parent
    while (current != null && current !== owner) {
        if (current is KtTryExpression && current.finallyBlock != null) return true
        current = current.parent
    }
    return false
}
