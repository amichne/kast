@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.CallbackSupplierInventoryEvidence
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality

/** Empty-stack callback entry reuses the existing finite supplier closure under the one native allowance. */
internal class NativeValueSeedCallbackFlow(
    private val request: ValueFlowRequest,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val allowance: IntellijRelationAllowance,
    private val retained: NativeValueFlowTransfers,
    private val permit: () -> Boolean,
) {
    private val role = request.source.role as ValueRole.FunctionArgument
    private val context =
        IntellijCallbackFlowContext(
            scope = scope,
            projection = projection,
            admitWork = { allowance.admitCallbackWork(request.budget.resources) },
            observation = projection.observation,
            callbackExpiry = ::expiry,
        )
    private val summaries = CallbackParameterSummaries(request.budget, projection.observation)
    private val supplies = NativeValueCallbackSupplies(request.source, role, scope, projection, context)

    fun read(): Refinement<Unit, ValueFlowRejection> {
        if (request.source.executionOwner.body != ValueExecutionBody.Declaration)
            return retained.unavailableCallback(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION)
        val formal =
            when (val value = supplies.readFormal()) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return retained.unavailableCallback(value.failure)
            }
        if (formal.formal.callable.valueIdentity != request.source.enclosing.valueIdentity)
            return retained.unavailableCallback(CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE)
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return retained.unavailableCallback(allowed.failure)
        }
        val final =
            projection.observation.observedAnalyze(formal.function) {
                formal.function.symbol.modality == KaSymbolModality.FINAL
            }
        if (!final) return retained.unavailableCallback(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
        return when (
            val inventory =
                IntellijCallbackSupplierInventory(context, summaries)
                    .read(formal.formal, formal.function, formal.parameter)
        ) {
            is CallbackSupplierInventoryEvidence.Exhaustive -> entries(inventory.inventory, formal)
            is CallbackSupplierInventoryEvidence.Unavailable -> retained.unavailableCallback(inventory.cause)
        }
    }

    private fun entries(
        inventory: CompleteCallbackSupplierInventory,
        formal: NativeCallbackSupplierFormal.Found,
    ): Refinement<Unit, ValueFlowRejection> {
        val partition =
            inventory.partitions.singleOrNull { it.formal == formal.formal }
                ?: return retained.unavailableCallback(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        if (partition.incoming.isNotEmpty()) {
            when (
                val recorded = retained.unavailableCallback(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
            ) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return recorded
            }
        }
        if (partition.suppliers.isEmpty()) {
            if (partition.incoming.isNotEmpty()) return Refinement.Refined(Unit)
            return retained.unavailableCallback(CallbackInvocationFlowCause.NO_INVOCATION_PROVEN)
        }
        val selected = NativeValueSeedCallbackSupplies(request.source, scope, projection, context, supplies)
        val flow = NativeValueCallbackFlow(request, role, scope, projection, allowance, retained, permit)
        for (supplier in partition.suppliers) {
            if (retained.resultCount >= request.budget.resources.resultLimit.value) {
                retained.unresolved(ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED)
                return Refinement.Refined(Unit)
            }
            when (val result = consumeSupplier(supplier, formal, selected, flow)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return result
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun consumeSupplier(
        supplier: CallbackParameterSupplier,
        formal: NativeCallbackSupplierFormal.Found,
        selected: NativeValueSeedCallbackSupplies,
        flow: NativeValueCallbackFlow,
    ): Refinement<Unit, ValueFlowRejection> =
        when (val value = selected.read(supplier, formal)) {
            is Refinement.Refined -> flow.consumeSeed(observe(value.value))
            is Refinement.Rejected -> retained.unavailableCallback(value.failure)
        }

    private fun observe(supply: NativeValueCallbackSupply): NativeValueCallbackObservation.Read {
        val preparation =
            when (val allowed = context.permit()) {
                is Refinement.Refined ->
                    nativePrepareCallbackEntry(supply, role, scope, projection.observation, request.source.basis)
                is Refinement.Rejected -> NativeValueCallbackEntryPreparation.Refused(allowed.failure)
            }
        val callback =
            readCallbackInvocationFlow(
                boundary = supply.literal,
                lexicalOwner = supply.lexicalOwner,
                scope = scope,
                projection = projection,
                admitWork = { allowance.admitCallbackWork(request.budget.resources) },
                summaries = summaries,
                callbackExpiry = ::expiry,
            )
        return NativeValueCallbackObservation.Read(supply, callback, preparation)
    }

    private fun expiry(): CallbackExpiryAdmission =
        if (allowance.elapsedLimitReached(request.budget.resources)) CallbackExpiryAdmission.EXPIRED
        else CallbackExpiryAdmission.CURRENT
}
