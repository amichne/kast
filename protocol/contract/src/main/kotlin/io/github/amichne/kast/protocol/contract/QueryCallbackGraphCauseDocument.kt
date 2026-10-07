package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Known finite graph rejection causes survive independently of optional retained evidence. */
@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
sealed interface QueryCallbackGraphCauseDocument {
    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: QueryCallbackFlowCauseDocument) : QueryCallbackGraphCauseDocument

    @Serializable
    @SerialName("INVALID_FLOW")
    data class InvalidFlow(val cause: QueryCallbackFlowFailureDocument) : QueryCallbackGraphCauseDocument

    @Serializable
    @SerialName("UNRESOLVED")
    data class Unresolved(
        val obligations: QueryCallbackGraphObligationsDocument,
        val scan: QueryCallbackInvocationScanDocument,
    ) : QueryCallbackGraphCauseDocument

    @Serializable
    @SerialName("UNPROVEN_POLICY")
    data class UnprovenPolicy(val policy: QueryCallbackGraphPolicyDocument) : QueryCallbackGraphCauseDocument

    @Serializable @SerialName("INCOMPLETE_SCAN") data object IncompleteScan : QueryCallbackGraphCauseDocument

    @Serializable
    @SerialName("UNSUPPORTED_DEFAULT_SUPPLY")
    data object UnsupportedDefaultSupply : QueryCallbackGraphCauseDocument

    @Serializable @SerialName("MISSING_NAMED_OWNER") data object MissingNamedOwner : QueryCallbackGraphCauseDocument

    @Serializable
    @SerialName("SUPPLIER_IDENTITY_MISMATCH")
    data object SupplierIdentityMismatch : QueryCallbackGraphCauseDocument

    @Serializable @SerialName("OUTSIDE_WORKSPACE") data object OutsideWorkspace : QueryCallbackGraphCauseDocument

    @Serializable @SerialName("NON_CALLABLE_TARGET") data object NonCallableTarget : QueryCallbackGraphCauseDocument

    @Serializable
    @SerialName("CALLABLE_VALUE_UNPROVEN")
    data object CallableValueUnproven : QueryCallbackGraphCauseDocument

    @Serializable
    @SerialName("INVALID_GRAPH_FAILURE")
    data class InvalidGraphFailure(val cause: QueryCallbackGraphProjectionFailureDocument) :
        QueryCallbackGraphCauseDocument

    @Serializable @SerialName("CYCLIC_ROUTE") data object CyclicRoute : QueryCallbackGraphCauseDocument
}

@Serializable
@kotlinx.serialization.json.JsonClassDiscriminator("type")
sealed interface QueryCallbackGraphPolicyDocument {
    @Serializable
    @SerialName("UNAVAILABLE")
    data class Unavailable(val cause: QueryCallbackNamedUnavailableCauseDocument) : QueryCallbackGraphPolicyDocument

    @Serializable
    @SerialName("EXCLUDED")
    data class Excluded(
        val reason: QueryCallbackGraphUnprovenExclusionReasonDocument,
        val excludedBoundary: QueryCallbackGraphBoundaryDocument,
    ) : QueryCallbackGraphPolicyDocument
}

@Serializable
enum class QueryCallbackGraphProjectionFailureDocument {
    ADMITTED_DIRECT_POLICY_REJECTED,
    ADMITTED_INLINE_POLICY_REJECTED,
    NON_INLINE_POLICY_REJECTED,
    NOINLINE_POLICY_REJECTED,
    CROSSINLINE_POLICY_REJECTED,
    EMPTY_NATIVE_OBLIGATIONS,
    NON_CANONICAL_NATIVE_OBLIGATIONS,
}

@Serializable
enum class QueryCallbackGraphUnprovenExclusionReasonDocument {
    STORED_CALLBACK,
    RETURNED_CALLBACK,
    DEFAULT_PARAMETER,
}
