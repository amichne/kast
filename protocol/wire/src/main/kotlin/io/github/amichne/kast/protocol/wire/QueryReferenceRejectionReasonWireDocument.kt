package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class QueryReferenceRejectionReasonWireDocument {
    @SerialName("revalidation-wrong-kind") REVALIDATION_WRONG_KIND,
    @SerialName("revalidation-unretained") REVALIDATION_UNRETAINED,
    @SerialName("revalidation-expired") REVALIDATION_EXPIRED,
    @SerialName("revalidation-capacity") REVALIDATION_CAPACITY,
    @SerialName("revalidation-work-limit-reached") REVALIDATION_WORK_LIMIT_REACHED,
    @SerialName("revalidation-time-limit-reached") REVALIDATION_TIME_LIMIT_REACHED,
    @SerialName("revalidation-retired") REVALIDATION_RETIRED,
    @SerialName("revalidation-capture-unavailable") REVALIDATION_CAPTURE_UNAVAILABLE,
    @SerialName("revalidation-workspace-mismatch") REVALIDATION_WORKSPACE_MISMATCH,
    @SerialName("revalidation-owner-mismatch") REVALIDATION_OWNER_MISMATCH,
    @SerialName("revalidation-workspace-not-ready") REVALIDATION_WORKSPACE_NOT_READY,
    @SerialName("revalidation-basis-moved") REVALIDATION_BASIS_MOVED,
    @SerialName("revalidation-content-changed") REVALIDATION_CONTENT_CHANGED,
    @SerialName("revalidation-content-uncommitted") REVALIDATION_CONTENT_UNCOMMITTED,
    @SerialName("revalidation-scope-rejected") REVALIDATION_SCOPE_REJECTED,
    @SerialName("revalidation-declaration-missing") REVALIDATION_DECLARATION_MISSING,
    @SerialName("revalidation-unsupported-declaration") REVALIDATION_UNSUPPORTED_DECLARATION,
    @SerialName("revalidation-ambiguous") REVALIDATION_AMBIGUOUS,
    @SerialName("revalidation-compiler-identity-changed") REVALIDATION_COMPILER_IDENTITY_CHANGED,
    @SerialName("revalidation-compiler-unavailable") REVALIDATION_COMPILER_UNAVAILABLE,
    @SerialName("wrong-kind") WRONG_KIND,
    @SerialName("malformed") MALFORMED,
    @SerialName("incompatible-workspace") INCOMPATIBLE_WORKSPACE,
    @SerialName("stale-generation") STALE_GENERATION,
    @SerialName("stale-authority") STALE_AUTHORITY,
    @SerialName("incompatible-authority") INCOMPATIBLE_AUTHORITY,
    @SerialName("incompatible-reference-version") INCOMPATIBLE_REFERENCE_VERSION,
}
