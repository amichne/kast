package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackDirectInvocationBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CompleteCallbackDirectInvocations
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

internal sealed interface SelectedCallbackInvocationRead {
    data object NotRequired : SelectedCallbackInvocationRead

    data class Formal(val upstream: NativeCallbackSupplierFormal.Found) : SelectedCallbackInvocationRead

    data class Available(val invocations: CompleteCallbackDirectInvocations) : SelectedCallbackInvocationRead

    data class Unavailable(val cause: CallbackInvocationFlowCause) : SelectedCallbackInvocationRead
}

/** Resolves the entire immutable receiver alternative inventory for this compiler-confirmed invocation. */
internal class IntellijSelectedCallbackInvocation(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(call: KtCallExpression, owner: CompilerGroundedSymbolEvidence): SelectedCallbackInvocationRead {
        when (val permit = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return unavailable(permit.failure)
        }
        if (!context.confirmsFunctionInvoke(call)) return SelectedCallbackInvocationRead.NotRequired
        val expression = receiver(call) ?: return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        when (val formal = IntellijCallbackSupplierFormal(context).read(expression, owner)) {
            is Refinement.Rejected -> return unavailable(formal.failure)
            is Refinement.Refined ->
                when (val upstream = formal.value) {
                    NativeCallbackSupplierFormal.NotFormal -> Unit
                    is NativeCallbackSupplierFormal.Found -> return formal(upstream)
                }
        }
        val values =
            when (
                val resolved = IntellijImmutableCallbackValueResolver(context, summaries).resolve(expression, owner)
            ) {
                is Refinement.Refined -> resolved.value
                is Refinement.Rejected -> return unavailable(resolved.failure)
            }
        val binding =
            when (val admitted = binding(call)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unavailable(admitted.failure)
            }
        val invocations =
            when (val admitted = CompleteCallbackDirectInvocations.fromCompiler(values, binding)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        return when (val retained = summaries.retention.admit(invocations.retainedBytes)) {
            is Refinement.Refined -> SelectedCallbackInvocationRead.Available(invocations)
            is Refinement.Rejected -> unavailable(retained.failure)
        }
    }

    private fun formal(upstream: NativeCallbackSupplierFormal.Found): SelectedCallbackInvocationRead =
        if (upstream.transfers.isEmpty()) SelectedCallbackInvocationRead.NotRequired
        else SelectedCallbackInvocationRead.Formal(upstream)

    private fun binding(
        call: KtCallExpression
    ): Refinement<CallbackDirectInvocationBinding, CallbackInvocationFlowCause> {
        val occurrence =
            context.occurrence(call.valueInvocationExpression())
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val invocationOwner =
            context.owner(call)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        return when (
            val admitted =
                CallbackDirectInvocationBinding.fromCompiler(
                    context.scope.request.subject.lease.identity,
                    occurrence,
                    invocationOwner,
                )
        ) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        }
    }

    private fun receiver(call: KtCallExpression): KtExpression? {
        val parent = call.parent as? KtQualifiedExpression
        return if (parent?.selectorExpression === call) parent.receiverExpression else call.calleeExpression
    }

    private fun unavailable(cause: CallbackInvocationFlowCause) = SelectedCallbackInvocationRead.Unavailable(cause)
}
