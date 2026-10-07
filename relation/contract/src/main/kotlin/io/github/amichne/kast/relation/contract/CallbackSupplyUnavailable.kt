package io.github.amichne.kast.relation.contract

/** Nonempty finite causes for a selected callback supply that could not be proven. */
class CallbackSupplyUnavailable(
    cause: CallbackInvocationFlowCause,
    additional: Set<CallbackInvocationFlowCause> = emptySet(),
) {
    val causes: Set<CallbackInvocationFlowCause> = java.util.Collections.unmodifiableSet((additional + cause).toSet())
}
