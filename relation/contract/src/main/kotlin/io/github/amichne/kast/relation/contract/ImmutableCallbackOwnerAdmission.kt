package io.github.amichne.kast.relation.contract

internal fun CallbackInvocationFlowFailure.immutableOwnerFailure(): ImmutableCallbackInvocationFlowFailure =
    when (this) {
        CallbackInvocationFlowFailure.BASIS_MISMATCH -> ImmutableCallbackInvocationFlowFailure.BASIS_MISMATCH
        CallbackInvocationFlowFailure.DUPLICATE_OWNER_BINDING ->
            ImmutableCallbackInvocationFlowFailure.DUPLICATE_OWNER_BINDING
        CallbackInvocationFlowFailure.INVALID_SCAN_PROOF -> ImmutableCallbackInvocationFlowFailure.INVALID_SCAN
        CallbackInvocationFlowFailure.MISSING_OBLIGATION -> ImmutableCallbackInvocationFlowFailure.MISSING_OBLIGATION
        CallbackInvocationFlowFailure.BODY_OUTSIDE_ARGUMENT,
        CallbackInvocationFlowFailure.PARAMETER_OUTSIDE_CALLABLE,
        CallbackInvocationFlowFailure.INVALID_PARAMETER_POSITION,
        CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_CALLABLE,
        CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_OWNER,
        CallbackInvocationFlowFailure.UNBOUND_INVOCATION,
        CallbackInvocationFlowFailure.DUPLICATE_INVOCATION,
        CallbackInvocationFlowFailure.UNSUPPORTED_CALLABLE_TRANSFER,
        CallbackInvocationFlowFailure.INVALID_CALLABLE_TRANSFER_PATH,
        CallbackInvocationFlowFailure.CALLABLE_TRANSFER_BINDING_MISMATCH,
        CallbackInvocationFlowFailure.INVOCATION_OUTSIDE_SUPPLYING_OWNER,
        CallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH,
        CallbackInvocationFlowFailure.INVALID_FORWARDING_PATH,
        CallbackInvocationFlowFailure.INVALID_DEPENDENCY_CONTRACT ->
            ImmutableCallbackInvocationFlowFailure.OWNER_BINDING_MISMATCH
    }
