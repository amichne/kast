package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope

internal fun CallbackFactoryBodyCalls.admitFactory(
    invocation: ValueInvocation,
    returned: ImmutableCallbackValue,
    captures: List<CallbackFactoryCapture>,
): Refinement<Unit, CallbackFactoryReturnFailure> {
    val anonymous = returned.origin as? ImmutableCallbackValueOrigin.Anonymous
    if (anonymous == null)
        return if (this == CallbackFactoryBodyCalls.NotApplicable) Refinement.Refined(Unit) else bodyMismatch()
    val complete = this as? CallbackFactoryBodyCalls.Exhaustive ?: return bodyMismatch()
    if (complete.body != anonymous.body) return bodyMismatch()
    if (!complete.admitsLibraryPolicy(invocation)) return bodyMismatch()
    if (complete.calls.any { !it.admitsBasis(invocation) })
        return Refinement.Rejected(CallbackFactoryReturnFailure.BASIS_MISMATCH)
    val actual = complete.calls.filterIsInstance<CallbackFactoryBodyCall.Captured>()
    val expectedCount = captures.sumOf {
        (it.content as? CallbackFactoryCaptureContent.Callable)?.invocations?.size?.toLong() ?: 0L
    }
    if (actual.size.toLong() != expectedCount || actual.any { !it.matches(captures) }) return bodyMismatch()
    return Refinement.Refined(Unit)
}

private fun CallbackFactoryBodyCalls.Exhaustive.admitsLibraryPolicy(invocation: ValueInvocation): Boolean {
    val libraryPolicy =
        (invocation.callable.scope as? SymbolSearchScope.Workspace)?.libraries ?: SymbolLibraryPolicy.EXCLUDE
    return libraryPolicy != SymbolLibraryPolicy.INCLUDE ||
        calls.none {
            it is CallbackFactoryBodyCall.Boundary &&
                it.disposition == SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED
        }
}

private fun CallbackFactoryBodyCall.admitsBasis(factory: ValueInvocation): Boolean =
    when (this) {
        is CallbackFactoryBodyCall.Named -> target.lease.identity == factory.basis
        is CallbackFactoryBodyCall.Boundary -> true
        is CallbackFactoryBodyCall.Captured ->
            formal.callable.lease.identity == factory.basis &&
                invocation.callableTransfers.all { it.source.basis == factory.basis } &&
                invocation.forwardings.all {
                    it.source.callable.lease.identity == factory.basis && it.target.invocation.basis == factory.basis
                }
    }

private fun CallbackFactoryBodyCall.Captured.matches(captures: List<CallbackFactoryCapture>): Boolean {
    val capture =
        captures.singleOrNull {
            it.binding.invocation.callable == formal.callable &&
                it.binding.position == formal.position &&
                it.binding.parameter == formal.parameter
        } ?: return false
    val content = capture.content as? CallbackFactoryCaptureContent.Callable ?: return false
    return invocation in content.invocations
}

private fun bodyMismatch() = Refinement.Rejected(CallbackFactoryReturnFailure.BODY_CALL_INVENTORY_MISMATCH)
