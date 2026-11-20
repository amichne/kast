package io.github.amichne.kast.relation.intellij

import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression

private val NULL_PREDICATE_OPERATORS = setOf(KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ)

/**
 * Kotlin evaluates equality against the literal `null` without invoking any `equals` overload, so the operand's value
 * never becomes the Boolean result. The predicate is accounted; it neither carries nor blocks the operand's value.
 */
internal fun KtBinaryExpression.isLiteralNullPredicateOperand(operand: KtExpression): Boolean {
    if (operationToken !in NULL_PREDICATE_OPERATORS) return false
    val other =
        when {
            left === operand -> right
            right === operand -> left
            else -> null
        }
    return other is KtConstantExpression && other.node.elementType == KtNodeTypes.NULL
}
