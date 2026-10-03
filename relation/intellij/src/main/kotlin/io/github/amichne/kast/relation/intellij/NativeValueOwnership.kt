package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

internal enum class NativeValueOwnership {
    ADMITTED,
    NESTED,
    UNAVAILABLE,
}

/** A containing range is insufficient proof of declaration ownership across nested execution boundaries. */
internal fun valueNativeOwnership(element: PsiElement, owner: PsiElement): NativeValueOwnership {
    var current: PsiElement? = element
    while (current != null) {
        if (current === owner) return NativeValueOwnership.ADMITTED
        when (current) {
            is KtNamedFunction,
            is KtLambdaExpression,
            is KtClassOrObject -> return NativeValueOwnership.NESTED
            is KtProperty -> if (!current.isLocal) return NativeValueOwnership.NESTED
        }
        current = current.parent
    }
    return NativeValueOwnership.UNAVAILABLE
}
