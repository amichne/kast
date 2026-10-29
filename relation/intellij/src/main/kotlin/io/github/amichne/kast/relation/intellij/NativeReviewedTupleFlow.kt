@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ReviewedCallValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryOperation
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall
import org.jetbrains.kotlin.psi.KtExpression

/** The compiler fixes the actual operand before a reviewed rule supplies component correspondence. */
internal fun nativeReviewedTupleRole(
    expression: KtExpression,
    call: KtExpression,
    execution: ValueExecutionOwner,
    context: IntellijCallbackFlowContext,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val binding =
        when (val read = readReviewedTupleBinding(call, execution, context)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return Refinement.Rejected(read.failure.valueFlowCause())
        }
    when (val permit = reviewedPermit(context)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return Refinement.Rejected(permit.failure.valueFlowCause())
    }
    return when (val selected = context.observation.observedAnalyze(call) { tupleInput(expression, call) }) {
        is NativeTupleInput.Receiver -> Refinement.Refined(ValueRole.Receiver(binding.invocation, selected.kind))
        is NativeTupleInput.Argument -> Refinement.Refined(ValueRole.Argument(binding.invocation, selected.position))
        NativeTupleInput.Unavailable -> Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    }
}

internal fun nativeReviewedTupleResult(
    request: ValueFlowRequest,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    allowance: IntellijRelationAllowance,
): Refinement<ReviewedValueTransfer, ValueFlowUnsupportedCause> {
    val invocation =
        when (val role = request.source.role) {
            is ValueRole.Receiver -> role.call
            is ValueRole.Argument -> role.call
            else -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
        }
    val host =
        when (val found = projection.subject(scope, request.source.enclosing)) {
            is IntellijRelationSubjectLookup.Found -> found.declaration
            is IntellijRelationSubjectLookup.Rejected ->
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
        }
    val call =
        host.exactValueElement(invocation.range)
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    val context = receiverContext(request, scope, projection, allowance)
    val binding =
        when (
            val read =
                nativeReviewedResultBinding(
                    call = call,
                    execution = request.source.executionOwner,
                    projection = projection,
                    context = context,
                )
        ) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return read
        }
    if (binding.invocation != invocation) return Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
    return when (binding.selected.operation) {
        ReviewedLibraryOperation.MAP_ENTRY_KEY,
        ReviewedLibraryOperation.MAP_ENTRY_VALUE,
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2 -> ReviewedValueTransfer.entryProject(request.source, binding)
        else ->
            if (invocation.signature is CanonicalCompilerSignature.Property)
                ReviewedValueTransfer.tupleProject(request.source, binding)
            else ReviewedValueTransfer.tupleConstruct(request.source, binding)
    }
}

private fun nativeReviewedResultBinding(
    call: KtExpression,
    execution: ValueExecutionOwner,
    projection: IntellijK2RelationProjection,
    context: IntellijCallbackFlowContext,
): Refinement<ReviewedCallValueBinding, ValueFlowUnsupportedCause> =
    if (call is org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry)
        nativeReviewedComponentBinding(
            entry = call,
            execution = execution,
            scope = context.scope,
            projection = projection,
            context = context,
        )
    else
        when (val selected = readReviewedTupleBinding(call, execution, context)) {
            is Refinement.Refined -> selected
            is Refinement.Rejected -> Refinement.Rejected(selected.failure.valueFlowCause())
        }

private fun KaSession.tupleInput(expression: KtExpression, call: KtExpression): NativeTupleInput {
    return when (val resolved = resolvedReviewedOrdinaryCall(call)) {
        is KaVariableAccessCall -> {
            val receiver = resolved.dispatchReceiver as? KaExplicitReceiverValue
            if (
                resolved.kind is KaVariableAccessCall.Kind.Read &&
                    resolved.extensionReceiver == null &&
                    receiver?.expression === expression
            )
                NativeTupleInput.Receiver(ValueReceiverKind.DISPATCH)
            else NativeTupleInput.Unavailable
        }
        is KaFunctionCall<*> -> {
            val receiver = nativeExplicitReceiverKind(expression, resolved.extensionReceiver, resolved.dispatchReceiver)
            if (receiver != null) NativeTupleInput.Receiver(receiver)
            else {
                val parameter = resolved.valueArgumentMapping[expression]?.symbol
                val position = resolved.signature.symbol.valueParameters.indexOf(parameter)
                when (val admitted = ValueArgumentPosition.parse(position)) {
                    is Refinement.Refined -> NativeTupleInput.Argument(admitted.value)
                    is Refinement.Rejected -> NativeTupleInput.Unavailable
                }
            }
        }
        else -> NativeTupleInput.Unavailable
    }
}

private sealed interface NativeTupleInput {
    data class Receiver(val kind: ValueReceiverKind) : NativeTupleInput

    data class Argument(val position: ValueArgumentPosition) : NativeTupleInput

    data object Unavailable : NativeTupleInput
}
