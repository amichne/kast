package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure
import java.util.Collections

sealed interface CallbackSummaryReadmissionFailure {
    data object WorkLimitReached : CallbackSummaryReadmissionFailure

    data object TimeLimitReached : CallbackSummaryReadmissionFailure

    data class Authority(val cause: LiveSemanticReadFailure) : CallbackSummaryReadmissionFailure

    data class MissingEndpoint(val identity: ValueDeclarationIdentity) : CallbackSummaryReadmissionFailure

    data class EndpointChanged(val identity: ValueDeclarationIdentity) : CallbackSummaryReadmissionFailure

    data class Callback(val cause: CallbackInvocationFlowFailure) : CallbackSummaryReadmissionFailure

    data class Formal(val cause: CallbackParameterIdentityFailure) : CallbackSummaryReadmissionFailure

    data class Invocation(val cause: ValueInvocationFailure) : CallbackSummaryReadmissionFailure

    data class Site(val cause: ValueSiteFailure) : CallbackSummaryReadmissionFailure

    data class Transfer(val cause: ValueTransferFailure) : CallbackSummaryReadmissionFailure

    data class Supplier(val cause: CallbackSupplierFailure) : CallbackSummaryReadmissionFailure

    data class ImmutableValue(val cause: ImmutableCallbackValueFailure) : CallbackSummaryReadmissionFailure

    data class Factory(val cause: CallbackFactoryReturnFailure) : CallbackSummaryReadmissionFailure

    data class FactoryBody(val cause: CallbackFactoryBodyFailure) : CallbackSummaryReadmissionFailure
}

/** Exact current compiler restorations, used only after the caller proves unchanged complete source dependencies. */
class CallbackEndpointReadmissions
private constructor(
    val authority: LiveSemanticReadAuthority,
    private val endpoints: Map<RelationEndpoint, RelationEndpoint.Resolved>,
    private val declarations:
        Map<
            io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence,
            io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence,
        >,
) {
    internal fun endpoint(previous: RelationEndpoint): CallbackReadmission<RelationEndpoint.Resolved> =
        when (val current = endpoints[previous]) {
            null -> Refinement.Rejected(CallbackSummaryReadmissionFailure.MissingEndpoint(previous.valueIdentity))
            else -> Refinement.Refined(current)
        }

    internal fun declaration(
        previous: io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
    ): CallbackReadmission<io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence> =
        when (val current = declarations[previous]) {
            null ->
                Refinement.Rejected(
                    CallbackSummaryReadmissionFailure.MissingEndpoint(
                        ValueDeclarationIdentity(previous.compilerIdentity, previous.file, previous.range)
                    )
                )
            else -> Refinement.Refined(current)
        }

    fun readmit(previous: CallbackParameterSummary): CallbackReadmission<CallbackParameterSummary> =
        when (val guarded = authority.withCurrentOwner { CallbackEvidenceReadmission(this).summary(previous) }) {
            is Refinement.Refined -> guarded.value
            is Refinement.Rejected -> Refinement.Rejected(CallbackSummaryReadmissionFailure.Authority(guarded.failure))
        }

    fun readmit(previous: CompleteCallbackSupplierInventory): CallbackReadmission<CompleteCallbackSupplierInventory> =
        when (val guarded = authority.withCurrentOwner { CallbackSupplierReadmission(this).inventory(previous) }) {
            is Refinement.Refined -> guarded.value
            is Refinement.Rejected -> Refinement.Rejected(CallbackSummaryReadmissionFailure.Authority(guarded.failure))
        }

    companion object {
        /** Native K2 must restore each endpoint on the admitted current basis; identity similarity is insufficient. */
        fun fromCompiler(
            authority: LiveSemanticReadAuthority,
            endpoints: Map<RelationEndpoint, RelationEndpoint.Resolved>,
            declarations:
                Map<
                    io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence,
                    io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence,
                > =
                emptyMap(),
        ): CallbackReadmission<CallbackEndpointReadmissions> {
            for ((previous, current) in endpoints) {
                if (
                    current.lease !== authority ||
                        previous.scope != current.scope ||
                        previous.constraints != current.constraints
                )
                    return Refinement.Rejected(
                        CallbackSummaryReadmissionFailure.EndpointChanged(previous.valueIdentity)
                    )
                if (RevalidatedRelationEndpoint.validate(previous, current.evidence) is Refinement.Rejected)
                    return Refinement.Rejected(
                        CallbackSummaryReadmissionFailure.EndpointChanged(previous.valueIdentity)
                    )
            }
            for ((previous, current) in declarations) {
                if (previous != current)
                    return Refinement.Rejected(
                        CallbackSummaryReadmissionFailure.EndpointChanged(
                            ValueDeclarationIdentity(previous.compilerIdentity, previous.file, previous.range)
                        )
                    )
            }
            return when (
                val guarded = authority.withCurrentOwner {
                    CallbackEndpointReadmissions(
                        authority,
                        Collections.unmodifiableMap(endpoints.toMap()),
                        Collections.unmodifiableMap(declarations.toMap()),
                    )
                }
            ) {
                is Refinement.Refined -> guarded
                is Refinement.Rejected ->
                    Refinement.Rejected(CallbackSummaryReadmissionFailure.Authority(guarded.failure))
            }
        }
    }
}

typealias CallbackReadmission<T> = Refinement<T, CallbackSummaryReadmissionFailure>

internal inline fun <T, R> CallbackReadmission<T>.then(next: (T) -> CallbackReadmission<R>): CallbackReadmission<R> =
    when (this) {
        is Refinement.Refined -> next(value)
        is Refinement.Rejected -> this
    }

internal fun <T, R> readmitEach(values: List<T>, next: (T) -> CallbackReadmission<R>): CallbackReadmission<List<R>> {
    val result = ArrayList<R>(values.size)
    for (value in values) when (val admitted = next(value)) {
        is Refinement.Refined -> result += admitted.value
        is Refinement.Rejected -> return admitted
    }
    return Refinement.Refined(result)
}
