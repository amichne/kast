@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** A rejected read retains its requested exact site and domain without manufacturing a native observation. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactReadRejectionDocument {
    @Serializable
    @SerialName("NATIVE")
    data class Native(
        val source: ImpactValueSiteReferenceDocument,
        val domain: ImpactRequestedBoundaryDocument,
        val cause: ImpactNativeReadRejectionDocument,
        val examinedWorkUnits: QueryDiscoveryCountDocument,
    ) : ImpactReadRejectionDocument

    @Serializable
    @SerialName("CONTRACT")
    data class Contract(
        val source: ImpactValueSiteReferenceDocument,
        val domain: ImpactRequestedBoundaryDocument,
        val cause: ImpactReadContractRejectionDocument,
        val examinedWorkUnits: QueryDiscoveryCountDocument,
    ) : ImpactReadRejectionDocument
}

@Serializable
enum class ImpactNativeReadRejectionDocument {
    STALE_SITE,
    OUTSIDE_DOMAIN,
    OWNER_UNAVAILABLE,
    AUTHORITY_MOVED,
    UNSUPPORTED_SEED,
    UNRESOLVED_SEED,
    NATIVE_UNAVAILABLE,
    GRANT_TOO_SMALL,
    NESTED_EXECUTION,
}

@Serializable
enum class ImpactReadContractRejectionDocument {
    INVALID_PROGRESS,
    WORK_LIMIT_EXCEEDED,
    RESULT_LIMIT_EXCEEDED,
    DETACHED_CAPACITY_EXCEEDED,
    DOMAIN_MISMATCH,
    SOURCE_MISMATCH,
    BASIS_MISMATCH,
    UNRESOLVED_OBLIGATIONS,
    MISSING_OBLIGATION,
}
