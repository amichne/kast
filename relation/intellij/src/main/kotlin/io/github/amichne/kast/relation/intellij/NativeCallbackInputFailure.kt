package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause

/** Keep source-flow, callback-reader and graph admission failures distinct at the native boundary. */
internal sealed interface NativeCallbackInputFailure {
    data class Value(val cause: ValueFlowUnsupportedCause) : NativeCallbackInputFailure

    data class Callback(val cause: CallbackInvocationFlowCause) : NativeCallbackInputFailure

    data class Contract(val cause: CallbackInvocationFlowFailure) : NativeCallbackInputFailure

    data class Graph(val cause: StaticCallbackGraphFailure) : NativeCallbackInputFailure
}

internal fun callbackInputRejected(
    cause: ValueFlowUnsupportedCause = ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE
): Refinement.Rejected<NativeCallbackInputFailure> = Refinement.Rejected(NativeCallbackInputFailure.Value(cause))
