package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackBodySupply
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import org.jetbrains.kotlin.psi.KtFunction

/** Owner evidence uses the ordinary binding and flow readers under the original request allowances. */
internal class IntellijCallbackOwnerBindingReader(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(
        literal: KtFunction,
        body: RelationCallableBody.Anonymous,
        enclosing: RelationEndpoint,
    ): Refinement<CallbackBodyBinding, CallbackInvocationFlowFailure> {
        val lexicalOwner =
            (enclosing as? RelationEndpoint.Resolved)?.evidence
                ?: return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        val supply = supply(literal) ?: return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        return when (
            val read =
                NativeCallbackInvocationFlow(
                        context = context,
                        lexicalOwner = lexicalOwner,
                        summaries = summaries,
                        literal = literal,
                        body = body,
                    )
                    .read()
        ) {
            is CallbackInvocationFlowRead.Observed -> {
                val binding =
                    when (
                        val admitted =
                            CallbackBodyBinding.fromCompiler(
                                body = body,
                                supply = supply,
                                binding = read.flow.binding,
                                obligations =
                                    read.flow.obligations + CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                            )
                    ) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return admitted
                    }
                binding.withExecution(read.flow)
            }
            is CallbackInvocationFlowRead.Unavailable -> unavailable(body, supply, read.cause)
            is CallbackInvocationFlowRead.ContractRejected -> Refinement.Rejected(read.cause)
            is CallbackInvocationFlowRead.Immutable -> {
                val owner =
                    when (val admitted = RelationCallableBody.Named.fromCompiler(lexicalOwner)) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected ->
                            return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
                    }
                val cause = unavailableCause(supply, CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
                when (val admitted = unavailable(body, supply, cause)) {
                    is Refinement.Refined -> admitted.value.withExecution(read.flow, owner)
                    is Refinement.Rejected -> admitted
                }
            }
        }
    }

    fun readUnavailable(
        literal: KtFunction,
        body: RelationCallableBody.Anonymous,
        cause: CallbackInvocationFlowCause,
    ): Refinement<CallbackBodyBinding, CallbackInvocationFlowFailure> {
        val supply = supply(literal) ?: return Refinement.Rejected(CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH)
        return unavailable(body, supply, cause)
    }

    private fun supply(literal: KtFunction): CallbackBodySupply? =
        when (val supply = classifyCallbackFunctionSupply(literal)) {
            is IntellijCallbackLambdaSupply.Argument ->
                supplyingCallbackCall(supply.argument)
                    ?.valueInvocationExpression()
                    ?.let(context::occurrence)
                    ?.let(CallbackBodySupply::Invocation)
            is IntellijCallbackLambdaSupply.DefaultParameter ->
                context.occurrence(supply.parameter)?.let(CallbackBodySupply::DefaultParameter)
            is IntellijCallbackLambdaSupply.Invocation ->
                context.occurrence(supply.call.valueInvocationExpression())?.let(CallbackBodySupply::DirectInvocation)
            is IntellijCallbackLambdaSupply.Returned ->
                context.occurrence(supply.occurrence)?.let(CallbackBodySupply::Returned)
            IntellijCallbackLambdaSupply.Stored -> CallbackBodySupply.Stored
            IntellijCallbackLambdaSupply.Unsupported -> CallbackBodySupply.Unsupported
        }

    private fun unavailable(
        body: RelationCallableBody.Anonymous,
        supply: CallbackBodySupply,
        cause: CallbackInvocationFlowCause,
    ): Refinement<CallbackBodyBinding, CallbackInvocationFlowFailure> {
        val bindingCause = unavailableCause(supply, cause)
        return CallbackBodyBinding.fromCompiler(
            body = body,
            supply = supply,
            binding = CallbackBindingEvidence.Unavailable(bindingCause),
            obligations = setOf(cause, bindingCause, CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
        )
    }

    private fun unavailableCause(supply: CallbackBodySupply, cause: CallbackInvocationFlowCause) =
        when (supply) {
            CallbackBodySupply.Stored -> CallbackInvocationFlowCause.STORED_CALLBACK
            is CallbackBodySupply.Returned -> CallbackInvocationFlowCause.RETURNED_CALLBACK
            CallbackBodySupply.Unsupported -> CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY
            is CallbackBodySupply.DefaultParameter,
            is CallbackBodySupply.DirectInvocation,
            is CallbackBodySupply.Invocation -> cause
        }
}
