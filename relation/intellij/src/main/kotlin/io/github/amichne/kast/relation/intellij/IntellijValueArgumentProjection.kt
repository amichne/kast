@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowSemantics
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

internal fun nativeValueArgumentReturn(
    request: ValueFlowRequest,
    element: PsiElement,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val source = request.source
    val argument =
        source.role as? ValueRole.Argument ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    when (val result = nativeTransparentWrapperReturn(argument, scope, projection)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> {
            if (result.failure != ValueFlowUnsupportedCause.UNMODELED_CALL) return result
            return if (argument.call.callable.kind == CompilerSymbolKind.CONSTRUCTOR)
                nativeValueConstructorArgument(source, scope, projection)
            else
                nativeValueSourceArgument(
                    request = request,
                    element = element,
                    scope = scope,
                    projection = projection,
                )
        }
    }
    val target =
        when (
            val result = argument.call.resultSite().withState(source.valuePath, source.callStack, source.conditions)
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        }
    return when (val result = ValueTransfer.fromCompiler(source, target, ValueTransferKind.WRAPPER_RETURN)) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }
}

internal fun nativeValueArgument(
    expression: KtExpression,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    executionOwner: ValueExecutionOwner,
    semantics: ValueFlowSemantics = ValueFlowSemantics.KOTLIN_FORWARD_V1,
): Refinement<ValueRole.Argument, ValueFlowUnsupportedCause> {
    val argument =
        expression.parent as? KtValueArgument
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    val call =
        when (val container = argument.parent) {
            is KtCallElement -> container
            is KtValueArgumentList -> container.parent as? KtCallElement
            else -> null
        }
    if (call == null) {
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    }
    val detached =
        when (val result = nativeArgumentBinding(call, expression, projection.observation)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> {
                return Refinement.Rejected(
                    when (result.failure) {
                        NativeArgumentBindingFailure.UNRESOLVED_REFERENCE ->
                            ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE
                        NativeArgumentBindingFailure.EXTERNAL_CALL -> ValueFlowUnsupportedCause.EXTERNAL_CALL
                    }
                )
            }
        }
    val endpoint =
        when (
            val result =
                nativeValueCallableEndpoint(
                    declaration = detached.declaration,
                    enclosing = executionOwner.enclosing,
                    scope = scope,
                    projection = projection,
                    semantics = semantics,
                )
        ) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    return when (val invocation = argumentInvocation(call, endpoint, executionOwner)) {
        is Refinement.Refined -> Refinement.Refined(ValueRole.Argument(invocation.value, detached.position))
        is Refinement.Rejected -> invocation
    }
}

private fun argumentInvocation(
    call: KtCallElement,
    endpoint: RelationEndpoint,
    executionOwner: ValueExecutionOwner,
): Refinement<ValueInvocation, ValueFlowUnsupportedCause> {
    val range =
        when (val result = argumentRange(call.valueInvocationExpression())) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> {
                return Refinement.Rejected(result.failure)
            }
        }
    return when (
        val result =
            ValueInvocation.fromCompiler(
                enclosing = executionOwner.enclosing,
                range = range,
                callable = endpoint,
                executionOwner = executionOwner,
            )
    ) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> {
            return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        }
    }
}

internal fun nativeValueCallableEndpoint(
    declaration: PsiNamedElement,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    semantics: ValueFlowSemantics = ValueFlowSemantics.KOTLIN_FORWARD_V1,
): Refinement<RelationEndpoint, ValueFlowUnsupportedCause> {
    when (scope.admitProviderSite(declaration.containingFile?.virtualFile)) {
        RelationProviderScopeAdmission.ADMITTED -> Unit
        RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.OUTSIDE_DOMAIN)
        RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED ->
            if (!declaration.retainsCompiledJavaIdentity(semantics))
                return Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
        RelationProviderScopeAdmission.UNAVAILABLE ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    }
    // The PSI declaration remains request-local; only its K2-projected endpoint is retained.
    val evidence =
        when (val result = projection.project(declaration)) {
            is IntellijRelationDeclarationProjection.Projected -> result.evidence
            IntellijRelationDeclarationProjection.Unsupported -> {
                return Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
            }
        }
    return when (
        val result =
            RelationEndpoint.resolve(
                enclosing.lease,
                scope.request.searchScope,
                evidence,
                scope.request.searchConstraints,
            )
    ) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> {
            return Refinement.Rejected(ValueFlowUnsupportedCause.OUTSIDE_DOMAIN)
        }
    }
}

/** The resolved argument can name the compiled target while its body remains outside the source domain. */
private fun PsiNamedElement.retainsCompiledJavaIdentity(semantics: ValueFlowSemantics): Boolean =
    semantics == ValueFlowSemantics.KOTLIN_JAVA_FORWARD_V2 && this is PsiMethod && this is PsiCompiledElement

private fun argumentRange(element: PsiElement): Refinement<ExactDeclarationTextRange, ValueFlowUnsupportedCause> =
    when (val result = ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset)) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }
