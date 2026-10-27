package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ValueCallFrame
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedFunction

/** An active implementation frame refines one exact call; its formal scan is never cached as an unqualified summary. */
internal fun readSelectedCallbackInputFlow(
    supplier: CallbackParameterSupplier,
    frames: List<ValueCallFrame>,
    literal: KtFunction,
    context: IntellijCallbackFlowContext,
    summaries: CallbackParameterSummaries,
): CallbackInvocationFlowRead = NativeSelectedCallbackInputFlow(context, summaries).read(supplier, frames, literal)

private class NativeSelectedCallbackInputFlow(
    private val context: IntellijCallbackFlowContext,
    private val summaries: CallbackParameterSummaries,
) {
    fun read(
        supplier: CallbackParameterSupplier,
        frames: List<ValueCallFrame>,
        literal: KtFunction,
    ): CallbackInvocationFlowRead {
        when (val permitted = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return CallbackInvocationFlowRead.Unavailable(permitted.failure)
        }
        val binding = supplier.binding
        val index = frames.indexOf(binding.frame)
        if (index < 0) return unavailable(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val owner = binding.invocationOwner
        if (
            owner !is RelationCallableBody.Named ||
                owner.compilerIdentity != binding.invocation.enclosing.compilerIdentity
        )
            return unavailable(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        val body =
            context.anonymous(literal) ?: return unavailable(CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE)
        val prepared =
            when (val read = prepare(supplier)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return unavailable(read.failure)
            }
        val scanned =
            when (
                val read =
                    observeCallbackCoverage(context.observation, CallbackCoverageStage.FORMAL, { it }) {
                        IntellijCallbackFlowScan(
                                context = context,
                                prepared = prepared,
                                summaries = summaries,
                                selectedFrames = frames.drop(index),
                            )
                            .read(supplier.formal)
                    }
            ) {
                is Refinement.Refined -> read.value.withSupplierQualification()
                is Refinement.Rejected -> return CallbackInvocationFlowRead.ContractRejected(read.failure)
            }
        return observed(body, binding, scanned)
    }

    private fun prepare(
        supplier: CallbackParameterSupplier
    ): Refinement<PreparedCallbackFlow, CallbackInvocationFlowCause> {
        val binding = supplier.binding
        val function =
            when (val restored = context.declaration(binding.callable)) {
                is IntellijRelationSubjectLookup.Found -> restored.declaration as? KtNamedFunction
                is IntellijRelationSubjectLookup.Rejected -> null
            } ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        if (function.bodyExpression == null)
            return Refinement.Rejected(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN)
        val parameter =
            function.valueParameters.getOrNull(binding.position.value)
                ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        if (context.occurrence(parameter) != binding.parameter)
            return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val call =
            when (val read = call(binding)) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val fresh =
            when (
                val read =
                    IntellijCallbackSupplierCalls(context, summaries)
                        .readExact(
                            call = call,
                            function = function,
                            parameter = parameter,
                            formal = supplier.formal,
                            frame = binding.frame,
                        )
            ) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        if (fresh.binding != binding)
            return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return Refinement.Refined(
            PreparedCallbackFlow(
                function = function,
                parameter = parameter,
                target = binding.callable,
                origin = PreparedCallbackOrigin.Argument(binding, call),
            )
        )
    }

    private fun call(binding: CallbackArgumentBinding): Refinement<KtCallElement, CallbackInvocationFlowCause> {
        val host =
            when (val restored = context.declaration(binding.invocation.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> restored.declaration
                is IntellijRelationSubjectLookup.Rejected ->
                    return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
            }
        val call =
            (host.exactValueElement(binding.invocation.range)?.nativeValueCallSyntax()
                    as? NativeValueCallSyntax.Explicit)
                ?.call ?: return Refinement.Rejected(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        return Refinement.Refined(call)
    }

    private fun observed(
        body: RelationCallableBody.Anonymous,
        binding: CallbackArgumentBinding,
        scanned: CallbackFormalScanSnapshot,
    ): CallbackInvocationFlowRead =
        when (
            val read =
                context.observed(
                    body = body,
                    binding = CallbackBindingEvidence.Bound(binding),
                    invocations = scanned.invocations,
                    obligations = scanned.obligations,
                    scan = scanned.scan,
                    forwarding = scanned.forwarding,
                )
        ) {
            is CallbackInvocationFlowRead.Observed ->
                when (val admitted = read.flow.withOwnerBindings(scanned.owners)) {
                    is Refinement.Refined -> CallbackInvocationFlowRead.Observed(admitted.value)
                    is Refinement.Rejected -> CallbackInvocationFlowRead.ContractRejected(admitted.failure)
                }
            is CallbackInvocationFlowRead.Immutable,
            is CallbackInvocationFlowRead.Unavailable,
            is CallbackInvocationFlowRead.ContractRejected -> read
        }

    private fun unavailable(cause: CallbackInvocationFlowCause) = CallbackInvocationFlowRead.Unavailable(cause)
}
