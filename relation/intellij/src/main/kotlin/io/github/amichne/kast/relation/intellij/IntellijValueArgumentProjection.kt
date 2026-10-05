@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

internal fun nativeValueArgument(
    expression: KtExpression,
    argument: KtValueArgument,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueRole.Argument, ValueFlowUnsupportedCause> {
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
        when (val result = nativeArgumentBinding(call, expression)) {
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
        when (val result = nativeArgumentEndpoint(detached, enclosing, scope, projection)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    val range =
        when (val result = argumentRange(call.valueInvocationExpression())) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> {
                return Refinement.Rejected(result.failure)
            }
        }
    val invocation =
        when (val result = ValueInvocation.fromCompiler(enclosing, range, endpoint)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> {
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
            }
        }
    return Refinement.Refined(ValueRole.Argument(invocation, detached.position))
}

private fun nativeArgumentEndpoint(
    detached: NativeArgument,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<RelationEndpoint, ValueFlowUnsupportedCause> {
    // The PSI declaration remains request-local; only its K2-projected endpoint is retained.
    val evidence =
        when (val result = projection.project(detached.declaration)) {
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

internal fun nativeArgumentBinding(
    call: KtCallElement,
    expression: KtExpression,
): Refinement<NativeArgument, NativeArgumentBindingFailure> =
    analyze(call) {
        val resolved =
            call.resolveCall() ?: return@analyze Refinement.Rejected(NativeArgumentBindingFailure.UNRESOLVED_REFERENCE)
        val callable =
            resolved.signature.symbol as? KaNamedFunctionSymbol
                ?: return@analyze Refinement.Rejected(NativeArgumentBindingFailure.UNRESOLVED_REFERENCE)
        val parameter =
            resolved.valueArgumentMapping[expression]?.symbol
                ?: return@analyze Refinement.Rejected(NativeArgumentBindingFailure.UNRESOLVED_REFERENCE)
        val position =
            when (val admitted = ValueArgumentPosition.parse(callable.valueParameters.indexOf(parameter))) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return@analyze Refinement.Rejected(NativeArgumentBindingFailure.UNRESOLVED_REFERENCE)
            }
        val declaration =
            callable.psi as? PsiNamedElement
                ?: return@analyze Refinement.Rejected(NativeArgumentBindingFailure.EXTERNAL_CALL)
        Refinement.Refined(NativeArgument(declaration, position))
    }

internal data class NativeArgument(val declaration: PsiNamedElement, val position: ValueArgumentPosition)

internal enum class NativeArgumentBindingFailure {
    UNRESOLVED_REFERENCE,
    EXTERNAL_CALL,
}

private fun argumentRange(element: PsiElement): Refinement<ExactDeclarationTextRange, ValueFlowUnsupportedCause> =
    when (val result = ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset)) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }
