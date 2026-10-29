@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** The reviewed artifact admits only the selected external receiver, without expanding the source domain. */
internal fun nativeReviewedReceiverRole(
    request: ValueFlowRequest,
    qualified: KtQualifiedExpression,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    allowance: IntellijRelationAllowance,
): Refinement<ValueRole.Receiver, ValueFlowUnsupportedCause> =
    nativeReviewedReceiverRole(
        receiverRange = request.source.range,
        executionOwner = request.source.executionOwner,
        qualified = qualified,
        context = receiverContext(request, scope, projection, allowance),
    )

internal fun nativeReviewedReceiverRole(
    receiverRange: ExactDeclarationTextRange,
    executionOwner: ValueExecutionOwner,
    qualified: KtQualifiedExpression,
    context: IntellijCallbackFlowContext,
): Refinement<ValueRole.Receiver, ValueFlowUnsupportedCause> {
    val enclosing = executionOwner.enclosing
    val binding =
        when (val read = receiverBinding(receiverRange, enclosing, qualified, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val target =
        when (
            val endpoint =
                RelationEndpoint.resolve(
                    enclosing.lease,
                    context.scope.request.searchScope,
                    binding.target,
                    context.scope.request.searchConstraints,
                )
        ) {
            is Refinement.Refined -> endpoint.value
            is Refinement.Rejected -> return receiverUnsupported()
        }
    return when (
        val invocation =
            ValueInvocation.fromCompiler(
                enclosing,
                binding.occurrence.range,
                target,
                executionOwner,
            )
    ) {
        is Refinement.Refined -> Refinement.Refined(ValueRole.Receiver(invocation.value, ValueReceiverKind.EXTENSION))
        is Refinement.Rejected -> receiverUnsupported()
    }
}

internal fun nativeReviewedReceiverResult(
    request: ValueFlowRequest,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    allowance: IntellijRelationAllowance,
): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
    val role = request.source.role as? ValueRole.Receiver ?: return receiverUnsupported()
    val host =
        when (val found = projection.subject(scope, request.source.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> found.declaration
            is IntellijRelationSubjectLookup.Rejected -> return receiverUnsupported()
        }
    val qualified = host.exactValueElement(role.call.range) as? KtQualifiedExpression ?: return receiverUnsupported()
    val context = receiverContext(request, scope, projection, allowance)
    val binding =
        when (val read = receiverBinding(request.source.range, request.source.enclosing, qualified, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    val literal = receiverLiteral(qualified) ?: return receiverUnsupported()
    return when (binding.selected.operation) {
        ReviewedLibraryOperation.ITERABLE_MAP -> nativeReviewedInput(request, binding, literal, context)
        ReviewedLibraryOperation.ALSO -> nativeReviewedIdentityResult(request, binding, literal, context)
        ReviewedLibraryOperation.LET -> receiverUnsupported()
    }
}

private fun nativeReviewedInput(
    request: ValueFlowRequest,
    binding: ReviewedCallbackValueBinding,
    literal: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
    when (context.claimWork()) {
        CallbackWorkAdmission.READY -> Unit
        CallbackWorkAdmission.WORK_LIMIT_REACHED ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        CallbackWorkAdmission.TIME_LIMIT_REACHED ->
            return Refinement.Rejected(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
    }
    val position =
        when (val admitted = ValueArgumentPosition.parse(0)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return receiverUnsupported()
        }
    return when (
        val parameter =
            context.observation.observedAnalyze(literal) {
                nativeCallbackInputParameter(literal, ValueExecutionOwner.reviewedCallback(binding), position)
            }
    ) {
        is Refinement.Refined -> ReviewedValueTransfer.callbackInput(request.source, binding, parameter.value)
        is Refinement.Rejected -> {
            context.observation.terminated(IntellijReadTermination.VALUE_REVIEWED_FORMAL_MISMATCH)
            Refinement.Rejected(
                when (val failure = parameter.failure) {
                    is NativeCallbackInputFailure.Value -> failure.cause
                    is NativeCallbackInputFailure.Callback,
                    is NativeCallbackInputFailure.Contract,
                    is NativeCallbackInputFailure.Graph -> ValueFlowUnsupportedCause.NESTED_EXECUTION
                }
            )
        }
    }
}

private fun nativeReviewedIdentityResult(
    request: ValueFlowRequest,
    binding: ReviewedCallbackValueBinding,
    literal: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
    val declared =
        when (val read = readDeclaredCallbackValueBinding(literal, request.source.enclosing, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    when (
        val normal = nativeCallbackNormalReturn(literal, context.observation, request.budget.resources.workAllowance)
    ) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return normal
    }
    return ReviewedValueTransfer.receiverResult(request.source, binding, declared)
}

private fun receiverBinding(
    receiverRange: ExactDeclarationTextRange,
    enclosing: RelationEndpoint,
    qualified: KtQualifiedExpression,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedCallbackValueBinding, ValueFlowUnsupportedCause> {
    if (sourceValueRange(qualified.receiverExpression) != receiverRange) return receiverUnsupported()
    val call = qualified.selectorExpression as? KtCallExpression ?: return receiverUnsupported()
    val receiver =
        context.observation.observedAnalyze(call) {
            val resolved = call.resolveCall() ?: return@observedAnalyze null
            val symbol =
                resolved.signature.symbol as? org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
                    ?: return@observedAnalyze null
            if (
                symbol.origin != org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin.LIBRARY ||
                    symbol.callableId?.asSingleFqName()?.asString() !in setOf("kotlin.also", "kotlin.collections.map")
            )
                return@observedAnalyze null
            nativeExplicitReceiverKind(
                qualified.receiverExpression,
                resolved.extensionReceiver,
                resolved.dispatchReceiver,
            )
        }
    if (receiver != ValueReceiverKind.EXTENSION) return receiverUnsupported()
    val literal = receiverLiteral(qualified) ?: return receiverUnsupported()
    return when (val read = readReviewedCallbackValueBinding(literal, enclosing, context)) {
        is Refinement.Refined ->
            when (read.value.selected.operation) {
                ReviewedLibraryOperation.ALSO,
                ReviewedLibraryOperation.ITERABLE_MAP -> read
                ReviewedLibraryOperation.LET -> receiverUnsupported()
            }
        is Refinement.Rejected -> Refinement.Rejected(read.failure.valueFlowCause())
    }
}

private fun receiverLiteral(qualified: KtQualifiedExpression): KtFunctionLiteral? {
    val call = qualified.selectorExpression as? KtCallExpression ?: return null
    return call.valueArguments
        .mapNotNull { it.getArgumentExpression() as? KtLambdaExpression }
        .singleOrNull()
        ?.functionLiteral
}

private fun receiverContext(
    request: ValueFlowRequest,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    allowance: IntellijRelationAllowance,
) =
    IntellijCallbackFlowContext(
        scope,
        projection,
        { allowance.admitCallbackWork(request.budget.resources) },
        projection.observation,
        {
            if (allowance.elapsedLimitReached(request.budget.resources)) CallbackExpiryAdmission.EXPIRED
            else CallbackExpiryAdmission.CURRENT
        },
    )

private fun receiverUnsupported() = Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
