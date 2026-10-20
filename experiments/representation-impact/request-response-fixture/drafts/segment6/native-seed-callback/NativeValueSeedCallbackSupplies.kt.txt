package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.CallbackSupplierSelection
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueSite

/** The inventory chooses a real supplying call. Fresh native owners reacquire that call and its actual literal. */
internal class NativeValueSeedCallbackSupplies(
    private val source: ValueSite,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val context: IntellijCallbackFlowContext,
    private val supplies: NativeValueCallbackSupplies,
) {
    private val frames = NativeValueCallerFrames(source, scope, projection)

    fun read(
        supplier: CallbackParameterSupplier,
        formal: NativeCallbackSupplierFormal.Found,
    ): Refinement<NativeValueCallbackSupply, CallbackInvocationFlowCause> {
        val origin =
            when (val value = origin(supplier, formal)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        val frame =
            when (val value = frame(supplier.binding.invocation)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        val supply =
            when (val value = supplies.suppliedLiteral(supplier.binding.invocation, formal, frame)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return value
            }
        return confirm(supply, origin)
    }

    private fun origin(
        supplier: CallbackParameterSupplier,
        formal: NativeCallbackSupplierFormal.Found,
    ): Refinement<RelationCallableBody.Anonymous, CallbackInvocationFlowCause> {
        if (supplier.selection !is CallbackSupplierSelection.Explicit) return unsupportedSeedSupply()
        val origin = supplier.value.origin as? ImmutableCallbackValueOrigin.Anonymous ?: return unsupportedSeedSupply()
        if (supplier.formal != formal.formal) return unsupportedSeedSupply()
        if (origin.body.file != supplier.binding.invocation.enclosing.file) return unsupportedSeedSupply()
        return Refinement.Refined(origin.body)
    }

    private fun confirm(
        supply: NativeValueCallbackSupply,
        expected: RelationCallableBody.Anonymous,
    ): Refinement<NativeValueCallbackSupply, CallbackInvocationFlowCause> {
        if (sourceValueRange(supply.literal) != expected.range) return unsupportedSeedSupply()
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val fresh = context.anonymous(supply.literal) ?: return unsupportedSeedSupply()
        if (fresh != expected) return unsupportedSeedSupply()
        return Refinement.Refined(supply)
    }

    private fun frame(expected: ValueInvocation): Refinement<ValueCallFrame, CallbackInvocationFlowCause> {
        when (val allowed = context.permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        val host =
            when (val value = projection.subject(scope, expected.enclosing)) {
                is IntellijRelationSubjectLookup.Found -> value
                is IntellijRelationSubjectLookup.Rejected -> return unsupportedSeedSupply()
            }
        val call = host.declaration.exactValueElement(expected.range).asCallbackCall() ?: return unsupportedSeedSupply()
        val fresh =
            when (val value = frames.invocation(call, host.declaration, expected.enclosing)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return unsupportedSeedSupply()
            }
        if (!sameInvocation(expected, fresh)) return unsupportedSeedSupply()
        return when (val value = frames.frame(fresh, NativeValueCallerTarget.Direct(source.enclosing))) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> unsupportedSeedSupply()
        }
    }

    private fun sameInvocation(expected: ValueInvocation, fresh: ValueInvocation): Boolean {
        if (expected != fresh || expected.enclosing != fresh.enclosing || expected.callable != fresh.callable)
            return false
        return expected.executionOwner.enclosing == fresh.executionOwner.enclosing &&
            expected.executionOwner.body == fresh.executionOwner.body
    }
}

private fun <T> unsupportedSeedSupply(): Refinement<T, CallbackInvocationFlowCause> =
    Refinement.Rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY)
