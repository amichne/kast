package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

internal fun admitCallbackFlowEvidence(
    basis: SemanticReadIdentity,
    body: RelationCallableBody.Anonymous,
    binding: CallbackBindingEvidence,
    invocations: List<CallbackParameterInvocation>,
    obligations: Set<CallbackInvocationFlowCause>,
): Refinement<Unit, CallbackInvocationFlowFailure> =
    when (binding) {
        is CallbackBindingEvidence.Bound -> admitBoundCallbackFlow(basis, body, binding.binding, invocations)
        is CallbackBindingEvidence.Default -> admitDefaultCallbackFlow(basis, body, binding.binding, invocations)
        is CallbackBindingEvidence.Direct -> admitDirectCallbackFlow(basis, body, binding.binding, invocations)
        is CallbackBindingEvidence.Unavailable -> admitUnboundCallbackFlow(binding, invocations, obligations)
    }

internal fun admitsExhaustiveCallbackScan(obligations: Set<CallbackInvocationFlowCause>): Boolean = obligations.all {
    it == CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION
}

internal fun admitCallbackScan(
    binding: CallbackBindingEvidence,
    scan: CallbackInvocationScan,
    invocations: List<CallbackParameterInvocation>,
    obligations: Set<CallbackInvocationFlowCause>,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    val valid =
        when (binding) {
            is CallbackBindingEvidence.Bound,
            is CallbackBindingEvidence.Default -> scan != CallbackInvocationScan.NOT_APPLICABLE
            is CallbackBindingEvidence.Direct -> scan == CallbackInvocationScan.NOT_APPLICABLE
            is CallbackBindingEvidence.Unavailable -> scan == CallbackInvocationScan.INCOMPLETE
        }
    if (!valid) return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_SCAN_PROOF)
    if (invocations.isEmpty() && obligations.isEmpty() && scan == CallbackInvocationScan.INCOMPLETE)
        return Refinement.Rejected(CallbackInvocationFlowFailure.MISSING_OBLIGATION)
    return Refinement.Refined(Unit)
}

/** Binding and invocation factories already preserve their own exact shape and local ownership proofs. */
internal fun admitBoundCallbackFlow(
    basis: SemanticReadIdentity,
    body: RelationCallableBody.Anonymous,
    binding: CallbackArgumentBinding,
    invocations: List<CallbackParameterInvocation>,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (binding.invocation.basis != basis) return Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
    if (body.file != binding.invocation.enclosing.file || !binding.invocation.range.containsValueRange(body.range))
        return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
    return admitInvocationRoutes(basis, binding.invocation.callable, binding.position, binding.parameter, invocations)
}

internal fun admitDefaultCallbackFlow(
    basis: SemanticReadIdentity,
    body: RelationCallableBody.Anonymous,
    binding: CallbackDefaultBinding,
    invocations: List<CallbackParameterInvocation>,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    if (binding.parameter.callable.lease.identity != basis)
        return Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
    if (body.file != binding.defaultValue.file || !binding.defaultValue.range.containsValueRange(body.range))
        return Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
    return admitInvocationRoutes(
        basis,
        binding.parameter.callable,
        binding.parameter.position,
        binding.parameter.parameter,
        invocations,
    )
}

internal fun admitDirectCallbackFlow(
    basis: SemanticReadIdentity,
    body: RelationCallableBody.Anonymous,
    binding: CallbackDirectInvocationBinding,
    invocations: List<CallbackParameterInvocation>,
): Refinement<Unit, CallbackInvocationFlowFailure> =
    when {
        binding.basis != basis -> Refinement.Rejected(CallbackInvocationFlowFailure.BASIS_MISMATCH)
        body.file != binding.occurrence.file || !binding.occurrence.range.containsValueRange(body.range) ->
            Refinement.Rejected(CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT)
        invocations.isNotEmpty() -> Refinement.Rejected(CallbackInvocationFlowFailure.UNBOUND_INVOCATION)
        else -> Refinement.Refined(Unit)
    }

internal fun admitInvocationRoutes(
    basis: SemanticReadIdentity,
    source: RelationEndpoint,
    position: ValueArgumentPosition,
    parameter: RelationOccurrence,
    invocations: List<CallbackParameterInvocation>,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    for (invocation in invocations) {
        when (val admitted = admitInvocationRoute(basis, source, position, parameter, invocation)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
    }
    return Refinement.Refined(Unit)
}

private fun admitInvocationRoute(
    basis: SemanticReadIdentity,
    source: RelationEndpoint,
    position: ValueArgumentPosition,
    parameter: RelationOccurrence,
    invocation: CallbackParameterInvocation,
): Refinement<Unit, CallbackInvocationFlowFailure> {
    var callable = source
    var formalPosition = position
    var formalParameter = parameter
    for (forwarded in invocation.forwardings) {
        if (forwarded.target.invocation.basis != basis)
            return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
        if (
            forwarded.source.callable.valueIdentity != callable.valueIdentity ||
                forwarded.source.position != formalPosition ||
                forwarded.source.parameter != formalParameter
        )
            return Refinement.Rejected(CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH)
        callable = forwarded.target.invocation.callable
        formalPosition = forwarded.target.position
        formalParameter = forwarded.target.parameter
    }
    if (invocation.owner.file != callable.file || !callable.range.containsValueRange(invocation.owner.range))
        return Refinement.Rejected(CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_CALLABLE)
    if (invocation.callableTransfers.any { !it.belongsTo(callable, basis) })
        return Refinement.Rejected(CallbackInvocationFlowFailure.CALLABLE_TRANSFER_BINDING_MISMATCH)
    return Refinement.Refined(Unit)
}

internal fun admitUnboundCallbackFlow(
    binding: CallbackBindingEvidence.Unavailable,
    invocations: List<CallbackParameterInvocation>,
    obligations: Set<CallbackInvocationFlowCause>,
): Refinement<Unit, CallbackInvocationFlowFailure> =
    if (invocations.isNotEmpty() || binding.cause !in obligations)
        Refinement.Rejected(CallbackInvocationFlowFailure.UNBOUND_INVOCATION)
    else Refinement.Refined(Unit)

/** Mapping inside an anonymous body does not prove how that body's supplying call is activated. */
internal fun callbackExecutionNeedsQualification(
    binding: CallbackBindingEvidence,
    invocations: List<CallbackParameterInvocation>,
): Boolean {
    val source =
        when (binding) {
            is CallbackBindingEvidence.Bound -> {
                if (!binding.binding.invocationOwner.isNamedOwnerOf(binding.binding.invocation.enclosing)) return true
                binding.binding.invocation.callable
            }
            is CallbackBindingEvidence.Default -> binding.binding.parameter.callable
            is CallbackBindingEvidence.Direct -> return binding.binding.owner is RelationCallableBody.Anonymous
            is CallbackBindingEvidence.Unavailable -> return false
        }
    return callbackInvocationsNeedQualification(source, invocations)
}

internal fun callbackInvocationsNeedQualification(
    source: RelationEndpoint,
    invocations: List<CallbackParameterInvocation>,
): Boolean = invocations.any { invocation ->
    val callable = invocation.forwardings.lastOrNull()?.target?.invocation?.callable ?: source
    invocation.forwardings.any { !it.target.invocationOwner.isNamedOwnerOf(it.source.callable) } ||
        !invocation.owner.isNamedOwnerOf(callable)
}

private fun RelationCallableBody.isNamedOwnerOf(callable: RelationEndpoint): Boolean =
    this is RelationCallableBody.Named && compilerIdentity == callable.compilerIdentity

private fun ValueTransfer.belongsTo(callable: RelationEndpoint, basis: SemanticReadIdentity): Boolean =
    source.basis == basis &&
        source.enclosing.valueIdentity == callable.valueIdentity &&
        target.enclosing.valueIdentity == callable.valueIdentity
