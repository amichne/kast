@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.jetbrains.kotlin.psi.KtFunctionLiteral
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
        when (val read = nativeReviewedReceiverBinding(receiverRange, enclosing, qualified, context)) {
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

internal fun nativeReviewedReceiverResults(
    request: ValueFlowRequest,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    allowance: IntellijRelationAllowance,
    retained: NativeValueFlowTransfers,
) {
    val qualified =
        when (val read = reviewedReceiverCall(request, scope, projection)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> {
                retained.unresolved(read.failure)
                return
            }
        }
    val context = receiverContext(request, scope, projection, allowance)
    val literal =
        when (val read = reviewedReceiverLiteral(qualified, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> {
                retained.unresolved(read.failure)
                return
            }
        }
    val binding =
        when (
            val read =
                nativeReviewedReceiverBinding(
                    receiverRange = request.source.range,
                    enclosing = request.source.enclosing,
                    qualified = qualified,
                    context = context,
                )
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> {
                retained.unresolved(read.failure)
                return
            }
        }
    reviewedReceiverTransfers(
        request = request,
        binding = binding,
        literal = literal,
        context = context,
        retained = retained,
    )
}

private fun reviewedReceiverCall(
    request: ValueFlowRequest,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<KtQualifiedExpression, ValueFlowUnsupportedCause> {
    val role = request.source.role as? ValueRole.Receiver ?: return receiverUnsupported()
    val host =
        when (val found = projection.subject(scope, request.source.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> found.declaration
            is IntellijRelationSubjectLookup.Rejected -> return receiverUnsupported()
        }
    return when (val qualified = host.exactValueElement(role.call.range) as? KtQualifiedExpression) {
        null -> receiverUnsupported()
        else -> Refinement.Refined(qualified)
    }
}

private fun reviewedReceiverTransfers(
    request: ValueFlowRequest,
    binding: ReviewedCallbackValueBinding,
    literal: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
    retained: NativeValueFlowTransfers,
) {
    when (binding.selected.operation) {
        ReviewedLibraryOperation.ITERABLE_MAP,
        ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL,
        ReviewedLibraryOperation.ITERABLE_ASSOCIATE ->
            retained.adoptReviewed(nativeReviewedInput(request, binding, literal, context))
        ReviewedLibraryOperation.ITERABLE_GROUP_BY,
        ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM ->
            nativeReviewedGroupingInputs(request, binding, literal, context, retained)
        ReviewedLibraryOperation.MAP_VALUES ->
            nativeReviewedMapInputs(
                source = request.source,
                binding = binding,
                literal = literal,
                context = context,
                retained = retained,
            )
        ReviewedLibraryOperation.ITERABLE_FILTER -> {
            retained.adoptReviewed(ReviewedValueTransfer.filteredResult(request.source, binding))
            retained.adoptReviewed(nativeReviewedInput(request, binding, literal, context))
        }
        ReviewedLibraryOperation.ITERABLE_SORTED_BY -> {
            retained.adoptReviewed(ReviewedValueTransfer.sortedResult(request.source, binding))
            retained.adoptReviewed(nativeReviewedInput(request, binding, literal, context))
        }
        ReviewedLibraryOperation.RUN_RECEIVER ->
            retained.adoptReviewed(nativeReviewedScopeReceiverInput(request.source, binding, literal, context))
        ReviewedLibraryOperation.APPLY -> {
            retained.adoptReviewed(nativeReviewedScopeReceiverInput(request.source, binding, literal, context))
            retained.adoptReviewed(nativeReviewedIdentityResult(request, binding, literal, context))
        }
        ReviewedLibraryOperation.ALSO ->
            retained.adoptReviewed(nativeReviewedIdentityResult(request, binding, literal, context))
        ReviewedLibraryOperation.RUN_TOP_LEVEL,
        ReviewedLibraryOperation.WITH,
        ReviewedLibraryOperation.LET,
        ReviewedLibraryOperation.TO,
        ReviewedLibraryOperation.PAIR_CONSTRUCTOR,
        ReviewedLibraryOperation.PAIR_FIRST,
        ReviewedLibraryOperation.PAIR_SECOND,
        ReviewedLibraryOperation.MAP_ENTRY_KEY,
        ReviewedLibraryOperation.MAP_ENTRY_VALUE,
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2 -> retained.unresolved(ValueFlowUnsupportedCause.EXTERNAL_CALL)
    }
}

private fun nativeReviewedInput(
    request: ValueFlowRequest,
    binding: ReviewedCallbackValueBinding,
    literal: KtFunctionLiteral,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> =
    when (val parameter = nativeReviewedInputParameter(binding, literal, context)) {
        is Refinement.Refined -> ReviewedValueTransfer.callbackInput(request.source, binding, parameter.value)
        is Refinement.Rejected -> parameter
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
    when (val normal = nativeCallbackNormalReturn(literal, context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return normal
    }
    return ReviewedValueTransfer.receiverResult(request.source, binding, declared)
}

internal fun receiverContext(
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
