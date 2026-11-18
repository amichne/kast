package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtThisExpression

internal fun PsiElement.exactSourceElements(range: ExactDeclarationTextRange): Sequence<PsiElement> =
    generateSequence(containingFile.findElementAt(range.startInclusive)) { it.parent }
        .takeWhile {
            it.textRange.startOffset >= textRange.startOffset && it.textRange.endOffset <= textRange.endOffset
        }
        .filter { it.textRange.startOffset == range.startInclusive && it.textRange.endOffset == range.endExclusive }

internal fun PsiElement.exactRetainedValueElement(range: ExactDeclarationTextRange): PsiElement? =
    if (this is PsiMethod) exactJavaValueElement(range) else exactValueElement(range)

/** Exact Kotlin semantic elements exclude same-range lexer tokens and argument container wrappers. */
internal fun PsiElement.exactValueElement(range: ExactDeclarationTextRange): KtExpression? =
    exactSourceElements(range).filterIsInstance<KtExpression>().firstOrNull()?.let { expression ->
        val semantic = expression.parent as? KtThisExpression
        if (semantic != null && semantic.instanceReference === expression && semantic.textRange == expression.textRange)
            semantic
        else expression
    }
