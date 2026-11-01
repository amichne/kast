package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

/** Keep the failed compiler boundary and its finite reason separate from the broad flow limitation. */
sealed interface ValueCallbackInputFailure {
    data class ImplementationRejected(val cause: RelationCompilerRejection) : ValueCallbackInputFailure

    @ConsistentCopyVisibility
    data class ImplementationIncomplete private constructor(val dispatch: ValueImplementationDispatch) :
        ValueCallbackInputFailure {
        enum class AdmissionFailure {
            EXHAUSTED_DISPATCH
        }

        companion object {
            fun fromCompiler(
                dispatch: ValueImplementationDispatch
            ): Refinement<ImplementationIncomplete, AdmissionFailure> =
                when (dispatch.coverage) {
                    is ValueImplementationCoverage.Incomplete -> Refinement.Refined(ImplementationIncomplete(dispatch))
                    is ValueImplementationCoverage.Exhausted -> Refinement.Rejected(AdmissionFailure.EXHAUSTED_DISPATCH)
                }
        }
    }

    data class Callback(val cause: CallbackInvocationFlowCause) : ValueCallbackInputFailure

    data class Contract(val cause: CallbackInvocationFlowFailure) : ValueCallbackInputFailure

    data class Graph(val cause: StaticCallbackGraphFailure) : ValueCallbackInputFailure

    fun matchesBasis(basis: SemanticReadIdentity): Boolean =
        when (this) {
            is ImplementationRejected,
            is Callback,
            is Contract -> true
            is ImplementationIncomplete -> dispatch.batch.request.subject.lease.identity == basis
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
                        is ImplementationRejected,
                        is Callback,
                        is Contract -> 0L
                        is ImplementationIncomplete -> dispatch.retainedIdentityBytes
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
        is ValueCallbackInputFailure.ImplementationRejected -> {
            field("IMPLEMENTATION_REJECTED")
            field(failure.cause.name)
        }
        is ValueCallbackInputFailure.ImplementationIncomplete -> {
            field("IMPLEMENTATION_INCOMPLETE")
            field(failure.dispatch.identity.canonical)
        }
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
