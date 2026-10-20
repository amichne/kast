package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.ValueCallBindingFailure
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer

internal sealed interface NativeValueCallbackObservation {
    data class Read(
        val supply: NativeValueCallbackSupply,
        val callback: CallbackInvocationFlowRead,
        val preparation: NativeValueCallbackEntryPreparation,
    ) : NativeValueCallbackObservation

    data class SupplyRefused(val cause: CallbackInvocationFlowCause) : NativeValueCallbackObservation
}

/** Callback routing borrows the one native allowance and keeps qualified flow beside positive static entry facts. */
internal class NativeValueCallbackFlow(
    private val request: ValueFlowRequest,
    private val role: ValueRole.FunctionArgument,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val allowance: IntellijRelationAllowance,
    private val retained: NativeValueFlowTransfers,
    private val permit: () -> Boolean,
) {
    private val context =
        IntellijCallbackFlowContext(
            scope = scope,
            projection = projection,
            admitWork = { allowance.admitCallbackWork(request.budget.resources) },
            observation = projection.observation,
            callbackExpiry = ::expiry,
        )

    fun observe(): NativeValueCallbackObservation {
        val supply =
            when (val value = NativeValueCallbackSupplies(request.source, role, scope, projection, context).read()) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return NativeValueCallbackObservation.SupplyRefused(value.failure)
            }
        val preparation =
            when (val allowed = context.permit()) {
                is Refinement.Refined ->
                    nativePrepareCallbackEntry(supply, role, scope, projection.observation, request.source.basis)
                is Refinement.Rejected -> NativeValueCallbackEntryPreparation.Refused(allowed.failure)
            }
        return NativeValueCallbackObservation.Read(
            supply,
            readCallbackInvocationFlow(
                boundary = supply.literal,
                lexicalOwner = supply.lexicalOwner,
                scope = scope,
                projection = projection,
                admitWork = { allowance.admitCallbackWork(request.budget.resources) },
                summaries = CallbackParameterSummaries(request.budget, projection.observation),
                callbackExpiry = ::expiry,
            ),
            preparation,
        )
    }

    fun read(): Refinement<Unit, ValueFlowRejection> = consume(observe())

    internal fun consume(observed: NativeValueCallbackObservation): Refinement<Unit, ValueFlowRejection> =
        when (observed) {
            is NativeValueCallbackObservation.SupplyRefused -> retained.unavailableCallback(observed.cause)
            is NativeValueCallbackObservation.Read ->
                consumeRead(observed, ::nativeSourceEntry, retained::unresolvedCallback)
        }

    internal fun consumeSeed(observed: NativeValueCallbackObservation.Read): Refinement<Unit, ValueFlowRejection> =
        consumeRead(observed, ::nativeSeedCallbackEntry, retained::unresolvedSeedCallback)

    private fun consumeRead(
        observed: NativeValueCallbackObservation.Read,
        enter: (ValueSite, ValueCallFrame, ValueFormalPosition) -> Refinement<ValueTransfer, ValueFlowUnsupportedCause>,
        question: (CallbackInvocationFlow) -> Refinement<Unit, ValueFlowRejection>,
    ): Refinement<Unit, ValueFlowRejection> {
        val preparation = observed.preparation
        if (preparation is NativeValueCallbackEntryPreparation.Refused) {
            when (val recorded = retained.unavailableCallback(preparation.cause)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return recorded
            }
        }
        return when (val read = observed.callback) {
            is CallbackInvocationFlowRead.Observed ->
                consumeFlow(observed.supply, read.flow, preparation, enter, question)
            is CallbackInvocationFlowRead.Unavailable -> retained.unavailableCallback(read.cause)
            is CallbackInvocationFlowRead.Immutable -> {
                retained.unresolved(ValueFlowUnsupportedCause.UNMODELED_CALL)
                Refinement.Refined(Unit)
            }
            is CallbackInvocationFlowRead.ContractRejected -> retained.rejectedCallback(read.cause)
        }
    }

    private fun consumeFlow(
        supply: NativeValueCallbackSupply,
        flow: CallbackInvocationFlow,
        preparation: NativeValueCallbackEntryPreparation,
        enter: (ValueSite, ValueCallFrame, ValueFormalPosition) -> Refinement<ValueTransfer, ValueFlowUnsupportedCause>,
        question: (CallbackInvocationFlow) -> Refinement<Unit, ValueFlowRejection>,
    ): Refinement<Unit, ValueFlowRejection> =
        when (val binding = flow.binding) {
            is CallbackBindingEvidence.Unavailable -> unavailable(binding, flow)
            is CallbackBindingEvidence.Bound,
            is CallbackBindingEvidence.Default,
            is CallbackBindingEvidence.Direct,
            is CallbackBindingEvidence.DependencyContract,
            is CallbackBindingEvidence.DeferredDependency -> entries(supply, flow, preparation, enter, question)
        }

    private fun unavailable(
        binding: CallbackBindingEvidence.Unavailable,
        flow: CallbackInvocationFlow,
    ): Refinement<Unit, ValueFlowRejection> {
        for (cause in flow.obligations + binding.cause) {
            when (val recorded = retained.unavailableCallback(cause)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return recorded
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun entries(
        supply: NativeValueCallbackSupply,
        flow: CallbackInvocationFlow,
        preparation: NativeValueCallbackEntryPreparation,
        enter: (ValueSite, ValueCallFrame, ValueFormalPosition) -> Refinement<ValueTransfer, ValueFlowUnsupportedCause>,
        question: (CallbackInvocationFlow) -> Refinement<Unit, ValueFlowRejection>,
    ): Refinement<Unit, ValueFlowRejection> {
        val selected =
            flow.invocations.filter {
                it.occurrence.range == role.call.range && it.occurrence.file == role.call.enclosing.file
            }
        if (selected.isEmpty() || flow.obligations.isNotEmpty() || flow.scan != CallbackInvocationScan.EXHAUSTIVE) {
            when (val value = question(flow)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return value
            }
        }
        if (selected.isEmpty()) return Refinement.Refined(Unit)
        val prepared =
            when (preparation) {
                is NativeValueCallbackEntryPreparation.Ready -> preparation
                is NativeValueCallbackEntryPreparation.Refused -> return Refinement.Refined(Unit)
                is NativeValueCallbackEntryPreparation.Unsupported -> {
                    retained.unresolved(preparation.cause)
                    return question(flow)
                }
            }
        if (prepared.body != flow.body) return Refinement.Rejected(ValueFlowRejection.STALE_SITE)
        for ((index, invocation) in selected.withIndex()) {
            when (val allowed = context.admitNativeScope()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return retained.unavailableCallback(allowed.failure)
            }
            if (index > 0 && !permit()) break
            when (
                val frame =
                    ValueCallFrame.callback(
                        role.call,
                        prepared.execution,
                        flow,
                        invocation,
                        supply.supplyingFrame,
                    )
            ) {
                is Refinement.Refined -> retained.adopt(enter(request.source, frame.value, prepared.formal))
                is Refinement.Rejected -> {
                    if (frame.failure == ValueCallBindingFailure.CALLBACK_PROOF_LIMIT_REACHED)
                        retained.unresolved(ValueFlowUnsupportedCause.CALLBACK_PROOF_LIMIT_REACHED)
                    else
                        when (val value = question(flow)) {
                            is Refinement.Refined -> Unit
                            is Refinement.Rejected -> return value
                        }
                }
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun expiry(): CallbackExpiryAdmission =
        if (allowance.elapsedLimitReached(request.budget.resources)) CallbackExpiryAdmission.EXPIRED
        else CallbackExpiryAdmission.CURRENT
}
