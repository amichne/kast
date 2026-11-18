package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowSemantics
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/** K2 supplies the selected Java method and actual-to-formal position at the Kotlin invocation. */
internal fun nativeJavaValueEntry(
    source: ValueSite,
    element: PsiElement,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val argument = source.role as? ValueRole.Argument ?: return unresolvedJavaArgument()
    val method =
        when (val selected = resolveJavaArgument(element, argument, projection.observation)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    val endpoint =
        when (
            val value =
                nativeValueCallableEndpoint(
                    declaration = method,
                    enclosing = source.enclosing,
                    scope = scope,
                    projection = projection,
                    semantics = ValueFlowSemantics.KOTLIN_JAVA_FORWARD_V2,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    if (endpoint.valueIdentity != argument.call.callable.valueIdentity) return unresolvedJavaArgument()
    if (!method.isSupportedJavaTarget()) return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    if (method is PsiCompiledElement || method.body == null)
        return Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)

    val parameter = method.parameterList.getParameter(argument.position.value) ?: return unresolvedJavaArgument()
    return when (val formal = nativeValueFormalParameter(argument.call.callable, argument.position, parameter)) {
        is Refinement.Refined -> nativeSourceEntry(source, argument.call, ValueFormalPosition.Parameter(formal.value))
        is Refinement.Rejected -> formal
    }
}

private fun resolveJavaArgument(
    element: PsiElement,
    argument: ValueRole.Argument,
    observation: IntellijReadObservation,
): Refinement<PsiMethod, ValueFlowUnsupportedCause> {
    val expression = element as? KtExpression ?: return unresolvedJavaArgument()
    val actual = expression.parent as? KtValueArgument ?: return unresolvedJavaArgument()
    val call = (actual.parent as? KtValueArgumentList)?.parent as? KtCallElement ?: return unresolvedJavaArgument()
    if (sourceValueRange(call.valueInvocationExpression()) != argument.call.range) return unresolvedJavaArgument()
    val binding =
        when (val result = nativeArgumentBinding(call, expression, observation)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return unresolvedJavaArgument()
        }
    val method =
        binding.declaration as? PsiMethod ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    if (binding.position != argument.position) return unresolvedJavaArgument()
    return Refinement.Refined(method)
}

internal fun PsiMethod.isSupportedJavaTarget(): Boolean =
    !isConstructor && !isVarArgs && hasModifierProperty(PsiModifier.STATIC)

private fun unresolvedJavaArgument(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
