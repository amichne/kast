@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtValueArgument

internal sealed interface CallbackForwardingRead {
    data class Observed(val prepared: PreparedCallbackFlow, val forwarding: CallbackParameterForwarding) :
        CallbackForwardingRead

    data class Unavailable(val cause: CallbackInvocationFlowCause) : CallbackForwardingRead
}

private data class NativeForwardingTarget(
    val function: KtNamedFunction,
    val parameter: KtParameter,
    val endpoint: RelationEndpoint.Resolved,
    val position: ValueArgumentPosition,
    val parameterOccurrence: RelationOccurrence,
)

/** Compiler mapping is read only after the exact incoming parameter reference has been confirmed. */
internal class IntellijCallbackForwardingReader(
    private val context: IntellijCallbackFlowContext,
    private val selectedFrames: List<ValueCallFrame> = emptyList(),
) {
    fun read(
        source: PreparedCallbackFlow,
        expression: KtNameReferenceExpression,
        argument: KtValueArgument,
        callableTransfers: List<ValueTransfer>,
    ): CallbackForwardingRead {
        when (val permitted = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return unavailable(permitted.failure)
        }
        val call =
            supplyingCallbackCall(argument)
                ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val argumentExpression =
            argument.getArgumentExpression()
                ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val mapped =
            when (val admitted = nativeArgumentBinding(call, argumentExpression, context.observation)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return unavailable(
                        when (admitted.failure) {
                            NativeArgumentBindingFailure.EXTERNAL_CALL -> CallbackInvocationFlowCause.EXTERNAL_CALLABLE
                            NativeArgumentBindingFailure.UNRESOLVED_REFERENCE ->
                                CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING
                        }
                    )
            }
        val selected = selectedFrames.filter { candidate ->
            candidate.invocation.enclosing.valueIdentity == source.target.valueIdentity &&
                candidate.invocation.range == context.range(call.valueInvocationExpression())
        }
        if (selected.size > 1) return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val frame = selected.singleOrNull()
        val target =
            when (val admitted = mappedTarget(mapped, frame)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unavailable(admitted.failure)
            }
        val binding =
            when (val admitted = argumentBinding(source = source, call = call, target = target, frame = frame)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unavailable(admitted.failure)
            }
        return observeForwarding(
            source,
            expression,
            target,
            PreparedCallbackOrigin.Argument(binding, call),
            callableTransfers,
        )
    }

    private fun mappedTarget(
        mapped: NativeArgument,
        frame: ValueCallFrame?,
    ): Refinement<NativeForwardingTarget, CallbackInvocationFlowCause> {
        val function =
            when (val selected = selectedFunction(mapped, frame)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return selected
            }
        if (selectedFrames.isNotEmpty() && function.bodyExpression == null)
            return Refinement.Rejected(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN)
        val file =
            function.containingFile.virtualFile
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        if (!context.scope.nativeScope.contains(file))
            return Refinement.Rejected(CallbackInvocationFlowCause.OUTSIDE_DOMAIN)
        val target =
            when (val admitted = context.target(function)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        if (frame != null && target != frame.callee.enclosing)
            return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val parameter =
            function.valueParameters.getOrNull(mapped.position.value)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        if (!context.observation.observedAnalyze(parameter) { parameter.symbol.returnType is KaFunctionType })
            return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val targetParameter =
            context.occurrence(parameter)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return Refinement.Refined(
            NativeForwardingTarget(
                function = function,
                parameter = parameter,
                endpoint = target,
                position = mapped.position,
                parameterOccurrence = targetParameter,
            )
        )
    }

    private fun selectedFunction(
        mapped: NativeArgument,
        frame: ValueCallFrame?,
    ): Refinement<KtNamedFunction, CallbackInvocationFlowCause> {
        val declared =
            mapped.declaration as? KtNamedFunction
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.EXTERNAL_CALLABLE)
        if (frame == null) return Refinement.Refined(declared)
        val declaredEndpoint =
            when (val restored = context.target(declared)) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> return restored
            }
        if (declaredEndpoint.valueIdentity != frame.invocation.callable.valueIdentity)
            return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val function =
            when (val restored = context.declaration(frame.callee.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> restored.declaration as? KtNamedFunction
                is IntellijRelationSubjectLookup.Rejected -> null
            } ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return Refinement.Refined(function)
    }

    private fun argumentBinding(
        source: PreparedCallbackFlow,
        call: KtCallElement,
        target: NativeForwardingTarget,
        frame: ValueCallFrame?,
    ): Refinement<CallbackArgumentBinding, CallbackInvocationFlowCause> {
        val range =
            context.range(call.valueInvocationExpression())
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val invocation =
            when (
                val admitted =
                    ValueInvocation.fromCompiler(source.target, range, frame?.invocation?.callable ?: target.endpoint)
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        if (frame != null && frame.invocation != invocation)
            return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val owner =
            context.owner(call)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        return when (
            val admitted =
                if (frame == null)
                    CallbackArgumentBinding.fromCompiler(
                        invocation = invocation,
                        invocationOwner = owner,
                        position = target.position,
                        parameter = target.parameterOccurrence,
                    )
                else
                    CallbackArgumentBinding.fromCompiler(
                        frame = frame,
                        invocationOwner = owner,
                        position = target.position,
                        parameter = target.parameterOccurrence,
                    )
        ) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun observeForwarding(
        source: PreparedCallbackFlow,
        expression: KtNameReferenceExpression,
        target: NativeForwardingTarget,
        origin: PreparedCallbackOrigin.Argument,
        callableTransfers: List<ValueTransfer>,
    ): CallbackForwardingRead {
        val identity =
            when (val admitted = sourceIdentity(source)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unavailable(admitted.failure)
            }
        val read =
            context.occurrence(expression)
                ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        return when (
            val admitted = CallbackParameterForwarding.fromCompiler(identity, read, origin.binding, callableTransfers)
        ) {
            is Refinement.Refined ->
                CallbackForwardingRead.Observed(
                    PreparedCallbackFlow(
                        function = target.function,
                        parameter = target.parameter,
                        target = target.endpoint,
                        origin = origin,
                    ),
                    admitted.value,
                )
            is Refinement.Rejected -> unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun sourceIdentity(
        source: PreparedCallbackFlow
    ): Refinement<CallbackParameterIdentity, CallbackInvocationFlowCause> {
        val sourcePosition =
            when (
                val admitted = ValueArgumentPosition.parse(source.function.valueParameters.indexOf(source.parameter))
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        val sourceParameter =
            context.occurrence(source.parameter)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return when (
            val admitted = CallbackParameterIdentity.fromCompiler(source.target, sourcePosition, sourceParameter)
        ) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun unavailable(cause: CallbackInvocationFlowCause) = CallbackForwardingRead.Unavailable(cause)
}
