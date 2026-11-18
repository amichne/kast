package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiTypes
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowSemantics
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueRole

internal enum class NativeJavaCallRejection(
    val seed: ValueProducerSeedRejection,
    val flow: ValueFlowUnsupportedCause,
) {
    UNRESOLVED(ValueProducerSeedRejection.UNRESOLVED_INVOCATION, ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE),
    UNSUPPORTED(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION, ValueFlowUnsupportedCause.UNMODELED_CALL),
}

internal class NativeJavaStaticCall
private constructor(
    val expression: PsiMethodCallExpression,
    val method: PsiMethod,
) {
    companion object {
        fun resolve(call: PsiMethodCallExpression): Refinement<NativeJavaStaticCall, NativeJavaCallRejection> {
            val selection = call.resolveMethodGenerics()
            val method =
                selection.element as? PsiMethod ?: return Refinement.Rejected(NativeJavaCallRejection.UNRESOLVED)
            if (!selection.isValidResult) return Refinement.Rejected(NativeJavaCallRejection.UNRESOLVED)
            val qualifier = call.methodExpression.qualifierExpression
            if (
                !method.isSupportedJavaTarget() ||
                    (qualifier != null && (qualifier as? PsiReferenceExpression)?.resolve() !is PsiClass)
            )
                return Refinement.Rejected(NativeJavaCallRejection.UNSUPPORTED)
            return Refinement.Refined(NativeJavaStaticCall(call, method))
        }
    }

    fun invocation(
        owner: ValueExecutionOwner,
        scope: CompiledRelationScope,
        projection: IntellijK2RelationProjection,
    ): Refinement<ValueInvocation, ValueFlowUnsupportedCause> {
        val endpoint =
            when (
                val value =
                    nativeValueCallableEndpoint(
                        declaration = method,
                        enclosing = owner.enclosing,
                        scope = scope,
                        projection = projection,
                        semantics = ValueFlowSemantics.KOTLIN_JAVA_FORWARD_V2,
                    )
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        return when (
            val value =
                ValueInvocation.fromCompiler(
                    enclosing = owner.enclosing,
                    range = sourceValueRange(expression),
                    callable = endpoint,
                    executionOwner = owner,
                )
        ) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        }
    }
}

internal class NativeJavaArgumentBinding
private constructor(
    val call: NativeJavaStaticCall,
    val position: ValueArgumentPosition,
) {
    companion object {
        fun resolve(expression: PsiExpression): Refinement<NativeJavaArgumentBinding, ValueFlowUnsupportedCause> {
            val arguments = expression.parent as? PsiExpressionList ?: return unsupportedJavaArgument()
            val invocation = arguments.parent as? PsiMethodCallExpression ?: return unsupportedJavaArgument()
            val call =
                when (val selected = NativeJavaStaticCall.resolve(invocation)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return Refinement.Rejected(selected.failure.flow)
                }
            val position =
                when (val value = ValueArgumentPosition.parse(arguments.expressions.indexOf(expression))) {
                    is Refinement.Refined -> value.value
                    is Refinement.Rejected -> return unsupportedJavaArgument()
                }
            val parameter = call.method.parameterList.getParameter(position.value) ?: return unsupportedJavaArgument()
            if (parameter.type != PsiTypes.intType() || expression.type != parameter.type)
                return unsupportedJavaArgument()
            return Refinement.Refined(NativeJavaArgumentBinding(call, position))
        }
    }

    fun role(
        owner: ValueExecutionOwner,
        scope: CompiledRelationScope,
        projection: IntellijK2RelationProjection,
    ): Refinement<ValueRole.Argument, ValueFlowUnsupportedCause> =
        when (val result = call.invocation(owner, scope, projection)) {
            is Refinement.Refined -> Refinement.Refined(ValueRole.Argument(result.value, position))
            is Refinement.Rejected -> result
        }
}

private fun unsupportedJavaArgument() = Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
