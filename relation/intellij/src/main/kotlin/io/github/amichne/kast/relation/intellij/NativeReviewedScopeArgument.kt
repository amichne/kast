@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** The selected with argument supplies the anonymous receiver through its distinct block formal. */
internal fun nativeReviewedArgumentRole(
    expression: KtExpression,
    invocation: KtExpression,
    executionOwner: ValueExecutionOwner,
    context: IntellijCallbackFlowContext,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val call = reviewedScopeCall(invocation)
    if (call != null) {
        when (val with = reviewedWithArgument(expression, call, executionOwner, context)) {
            is Refinement.Refined -> return with
            is Refinement.Rejected -> if (with.failure != ValueFlowUnsupportedCause.EXTERNAL_CALL) return with
        }
    }
    return nativeReviewedTupleRole(expression, invocation, executionOwner, context)
}

private fun reviewedWithArgument(
    expression: KtExpression,
    call: KtCallExpression,
    executionOwner: ValueExecutionOwner,
    context: IntellijCallbackFlowContext,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val position =
        when (val read = reviewedWithPosition(expression, call, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val input =
        when (val read = reviewedWithInput(call, executionOwner.enclosing, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val endpoint =
        when (
            val read =
                RelationEndpoint.resolve(
                    executionOwner.enclosing.lease,
                    context.scope.request.searchScope,
                    input.binding.target,
                    context.scope.request.searchConstraints,
                )
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
        }
    return when (
        val invocation =
            ValueInvocation.fromCompiler(
                enclosing = executionOwner.enclosing,
                range = input.binding.occurrence.range,
                callable = endpoint,
                executionOwner = executionOwner,
            )
    ) {
        is Refinement.Refined -> Refinement.Refined(ValueRole.Argument(invocation.value, position))
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }
}

private fun reviewedWithPosition(
    expression: KtExpression,
    call: KtCallExpression,
    context: IntellijCallbackFlowContext,
): Refinement<ValueArgumentPosition, ValueFlowUnsupportedCause> {
    when (val permit = reviewedPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return Refinement.Rejected(permit.failure.valueFlowCause())
    }
    return context.observation.observedAnalyze(call) {
        val resolved = call.resolveCall()
        val symbol = resolved?.signature?.symbol as? KaNamedFunctionSymbol
        if (
            symbol?.origin != KaSymbolOrigin.LIBRARY || symbol.callableId?.asSingleFqName()?.asString() != "kotlin.with"
        )
            return@observedAnalyze Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
        val formal = resolved?.valueArgumentMapping?.get(expression)?.symbol
        when (val parsed = ValueArgumentPosition.parse(symbol.valueParameters.indexOf(formal))) {
            is Refinement.Refined ->
                if (parsed.value.value == 0) parsed
                else Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        }
    }
}

internal fun nativeReviewedWithInput(
    request: ValueFlowRequest,
    context: IntellijCallbackFlowContext,
    projection: IntellijK2RelationProjection,
    retained: NativeValueFlowTransfers,
) {
    val role = request.source.role as? ValueRole.Argument
    val host = projection.subject(context.scope, request.source.enclosing)
    val call =
        if (host is IntellijRelationSubjectLookup.Found && role != null)
            reviewedScopeCall(host.declaration.exactValueElement(role.call.range))
        else null
    if (call == null) {
        retained.unresolved(ValueFlowUnsupportedCause.EXTERNAL_CALL)
        return
    }
    when (val read = reviewedWithInput(call, request.source.enclosing, context)) {
        is Refinement.Rejected -> retained.unresolved(read.failure)
        is Refinement.Refined ->
            retained.adoptReviewed(
                nativeReviewedScopeReceiverInput(
                    source = request.source,
                    binding = read.value.binding,
                    literal = read.value.literal,
                    context = context,
                )
            )
    }
}

private data class NativeReviewedWithInput(val binding: ReviewedCallbackValueBinding, val literal: KtFunctionLiteral)

private fun reviewedWithInput(
    call: KtCallExpression,
    enclosing: RelationEndpoint,
    context: IntellijCallbackFlowContext,
): Refinement<NativeReviewedWithInput, ValueFlowUnsupportedCause> {
    val position =
        when (val parsed = ValueArgumentPosition.parse(1)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    val literal =
        when (val read = nativeReviewedCallLiteral(call, position, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    return when (val read = readReviewedCallbackValueBinding(literal, enclosing, context)) {
        is Refinement.Rejected -> Refinement.Rejected(read.failure.valueFlowCause())
        is Refinement.Refined ->
            if (read.value.selected.operation == ReviewedLibraryOperation.WITH)
                Refinement.Refined(NativeReviewedWithInput(read.value, literal))
            else Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
    }
}

private fun reviewedScopeCall(element: com.intellij.psi.PsiElement?): KtCallExpression? =
    when (element) {
        is KtCallExpression -> element
        is KtQualifiedExpression -> element.selectorExpression as? KtCallExpression
        else -> null
    }
