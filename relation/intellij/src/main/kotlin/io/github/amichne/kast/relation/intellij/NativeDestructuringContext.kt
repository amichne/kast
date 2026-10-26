package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtParameter

/** Component invocation sites preserve their distinct initializer, parameter, and iteration owners. */
internal sealed interface NativeDestructuringContext {
    data class Local(val initializer: KtExpression) : NativeDestructuringContext

    data class Parameter(val parameter: KtParameter, val function: KtFunctionLiteral) : NativeDestructuringContext

    data class Iteration(val loop: KtForExpression, val range: KtExpression) : NativeDestructuringContext

    data object Unsupported : NativeDestructuringContext
}

/** The compiler-selected component result does not establish provenance for its parameter or iteration input. */
internal fun KtDestructuringDeclaration.nativeDestructuringContext(): NativeDestructuringContext =
    if (isFullForm || hasSquareBrackets()) NativeDestructuringContext.Unsupported
    else
        when (val container = parent) {
            is KtBlockExpression ->
                when (val initializer = initializer) {
                    null -> NativeDestructuringContext.Unsupported
                    else -> NativeDestructuringContext.Local(initializer)
                }
            is KtParameter -> if (container.isLambdaParameter) parameterContext(container) else iterationContext()
            else -> iterationContext()
        }

private fun KtDestructuringDeclaration.parameterContext(parameter: KtParameter): NativeDestructuringContext =
    if (!parameter.isLambdaParameter || parameter.destructuringDeclaration !== this)
        NativeDestructuringContext.Unsupported
    else
        when (val function = parameter.ownerFunction) {
            is KtFunctionLiteral -> NativeDestructuringContext.Parameter(parameter, function)
            else -> NativeDestructuringContext.Unsupported
        }

private fun KtDestructuringDeclaration.iterationContext(): NativeDestructuringContext =
    when (val loop = PsiTreeUtil.getParentOfType(this, KtForExpression::class.java, true)) {
        null -> NativeDestructuringContext.Unsupported
        else ->
            if (loop.destructuringDeclaration !== this) NativeDestructuringContext.Unsupported
            else
                when (val range = loop.loopRange) {
                    null -> NativeDestructuringContext.Unsupported
                    else -> NativeDestructuringContext.Iteration(loop, range)
                }
    }
