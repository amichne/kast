package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

/** Keep the failed compiler boundary and its finite reason separate from the broad flow limitation. */
sealed interface ValueCallbackInputFailure {
    data class Callback(val cause: CallbackInvocationFlowCause) : ValueCallbackInputFailure

    data class Contract(val cause: CallbackInvocationFlowFailure) : ValueCallbackInputFailure

    data class Graph(val cause: StaticCallbackGraphFailure) : ValueCallbackInputFailure

    fun matchesBasis(basis: SemanticReadIdentity): Boolean =
        when (this) {
            is Callback,
            is Contract -> true
            is Graph ->
                when (val failure = cause) {
                    is StaticCallbackGraphFailure.ImmutableUnresolved -> failure.flow.basis == basis
                    is StaticCallbackGraphFailure.Unresolved -> failure.flow.basis == basis
                    is StaticCallbackGraphFailure.Unavailable,
                    is StaticCallbackGraphFailure.InvalidFlow,
                    is StaticCallbackGraphFailure.UnprovenPolicy,
                    StaticCallbackGraphFailure.CyclicRoute,
                    StaticCallbackGraphFailure.IncompleteScan,
                    StaticCallbackGraphFailure.UnsupportedDefaultSupply,
                    StaticCallbackGraphFailure.MissingNamedOwner,
                    StaticCallbackGraphFailure.SupplierIdentityMismatch,
                    StaticCallbackGraphFailure.OutsideWorkspace,
                    StaticCallbackGraphFailure.NonCallableTarget -> true
                }
        }

    val retainedBytes: Long
        get() =
            256L
                .addBytes(canonicalProjection().length.toLong().multiplyBytes(2))
                .addBytes(
                    when (this) {
                        is Callback,
                        is Contract -> 0L
                        is Graph ->
                            when (val failure = cause) {
                                is StaticCallbackGraphFailure.ImmutableUnresolved -> failure.flow.retainedBytes
                                is StaticCallbackGraphFailure.Unresolved -> failure.flow.retainedBytes
                                is StaticCallbackGraphFailure.Unavailable,
                                is StaticCallbackGraphFailure.InvalidFlow,
                                is StaticCallbackGraphFailure.UnprovenPolicy,
                                StaticCallbackGraphFailure.CyclicRoute,
                                StaticCallbackGraphFailure.IncompleteScan,
                                StaticCallbackGraphFailure.UnsupportedDefaultSupply,
                                StaticCallbackGraphFailure.MissingNamedOwner,
                                StaticCallbackGraphFailure.SupplierIdentityMismatch,
                                StaticCallbackGraphFailure.OutsideWorkspace,
                                StaticCallbackGraphFailure.NonCallableTarget -> 0L
                            }
                    }
                )
}

internal fun ValueCallbackInputFailure.canonicalProjection(): String = buildString {
    when (val failure = this@canonicalProjection) {
        is ValueCallbackInputFailure.Callback -> {
            field("CALLBACK")
            field(failure.cause.name)
        }
        is ValueCallbackInputFailure.Contract -> {
            field("CONTRACT")
            field(failure.cause.name)
        }
        is ValueCallbackInputFailure.Graph -> {
            field("GRAPH")
            callbackGraphFailure(failure.cause)
        }
    }
}

private fun StringBuilder.callbackGraphFailure(failure: StaticCallbackGraphFailure) {
    when (failure) {
        is StaticCallbackGraphFailure.Unavailable -> {
            field("UNAVAILABLE")
            field(failure.cause.name)
        }
        is StaticCallbackGraphFailure.InvalidFlow -> {
            field("INVALID_FLOW")
            field(failure.cause.name)
        }
        is StaticCallbackGraphFailure.ImmutableUnresolved -> {
            field("IMMUTABLE_UNRESOLVED")
            field(failure.flow.canonicalProjection())
        }
        is StaticCallbackGraphFailure.Unresolved -> {
            field("UNRESOLVED")
            field(failure.flow.canonicalProjection())
        }
        is StaticCallbackGraphFailure.UnprovenPolicy -> {
            field("UNPROVEN_POLICY")
            when (val policy = failure.policy) {
                CallbackNamedCallPolicy.AdmittedDirect -> field("ADMITTED_DIRECT")
                CallbackNamedCallPolicy.AdmittedInline -> field("ADMITTED_INLINE")
                is CallbackNamedCallPolicy.Unavailable -> {
                    field("UNAVAILABLE")
                    field(policy.cause.name)
                }
                is CallbackNamedCallPolicy.Excluded -> {
                    field("EXCLUDED")
                    field(policy.reason.name)
                    occurrence(policy.boundary)
                }
            }
        }
        StaticCallbackGraphFailure.CyclicRoute -> field("CYCLIC_ROUTE")
        StaticCallbackGraphFailure.IncompleteScan -> field("INCOMPLETE_SCAN")
        StaticCallbackGraphFailure.UnsupportedDefaultSupply -> field("UNSUPPORTED_DEFAULT_SUPPLY")
        StaticCallbackGraphFailure.MissingNamedOwner -> field("MISSING_NAMED_OWNER")
        StaticCallbackGraphFailure.SupplierIdentityMismatch -> field("SUPPLIER_IDENTITY_MISMATCH")
        StaticCallbackGraphFailure.OutsideWorkspace -> field("OUTSIDE_WORKSPACE")
        StaticCallbackGraphFailure.NonCallableTarget -> field("NON_CALLABLE_TARGET")
    }
}
