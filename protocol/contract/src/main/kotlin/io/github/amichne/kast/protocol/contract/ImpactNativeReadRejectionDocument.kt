@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** Each case carries its own explanation. A run-scoped diagnostic cannot replace this evidence. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactNativeReadRejectionDocument {
    @Serializable @SerialName("STALE_SITE") data object STALE_SITE : ImpactNativeReadRejectionDocument

    @Serializable @SerialName("OUTSIDE_DOMAIN") data object OUTSIDE_DOMAIN : ImpactNativeReadRejectionDocument

    @Serializable @SerialName("OWNER_UNAVAILABLE") data object OWNER_UNAVAILABLE : ImpactNativeReadRejectionDocument

    @Serializable @SerialName("AUTHORITY_MOVED") data object AUTHORITY_MOVED : ImpactNativeReadRejectionDocument

    @Serializable @SerialName("UNSUPPORTED_SEED") data object UNSUPPORTED_SEED : ImpactNativeReadRejectionDocument

    @Serializable @SerialName("UNRESOLVED_SEED") data object UNRESOLVED_SEED : ImpactNativeReadRejectionDocument

    @Serializable @SerialName("GRANT_TOO_SMALL") data object GRANT_TOO_SMALL : ImpactNativeReadRejectionDocument

    @Serializable @SerialName("NESTED_EXECUTION") data object NESTED_EXECUTION : ImpactNativeReadRejectionDocument

    @Serializable sealed interface Operational : ImpactNativeReadRejectionDocument

    @Serializable
    @SerialName("HOST_UNAVAILABLE")
    data class HostUnavailable(
        val operation: ImpactNativeOperationDocument,
        val prerequisite: ImpactNativePrerequisiteDocument,
    ) : Operational

    @Serializable
    @SerialName("UNEXPECTED_NATIVE_FAILURE")
    data class Unexpected(
        val operation: ImpactNativeOperationDocument,
        val kind: ImpactNativeUnexpectedKindDocument,
        val exceptionType: String,
        val adapterFrames: List<String>,
    ) : Operational

    @Serializable
    @SerialName("NESTED_RELATION_REJECTED")
    data class RelationRejected(
        val origin: ImpactNativeRelationOriginDocument,
        val rejection: ImpactNativeRelationRejectionDocument,
    ) : Operational

    @Serializable
    @SerialName("PORT_UNCONFIGURED")
    data class PortUnconfigured(val operation: ImpactNativeOperationDocument) : Operational
}

@Serializable
enum class ImpactNativeOperationDocument {
    FLOW_READ,
    SITE_RESOLUTION,
    PRODUCER_SEED,
    MODEL_DECLARATION,
    MODEL_SITE,
}

@Serializable
enum class ImpactNativePrerequisiteDocument {
    PROJECT_DISPOSED,
    INDEXES_UNAVAILABLE,
}

@Serializable
enum class ImpactNativeUnexpectedKindDocument {
    RUNTIME,
    LINKAGE,
}

@Serializable
enum class ImpactNativeRelationOriginDocument {
    IMPLEMENTATION_DISPATCH,
    RETURN_CALLER_REFERENCES,
}

/** Received cause only. Index unavailability does not prove that the nested adapter saw no exception. */
@Serializable
enum class ImpactNativeRelationRejectionDocument {
    WORKSPACE_ROOT_MISMATCH,
    GENERATION_MOVED,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_SELECTOR,
    OUTSIDE_SCOPE,
    AMBIGUOUS_SUBJECT,
    UNSUPPORTED_SUBJECT,
    COMPILER_IDENTITY_UNAVAILABLE,
    CONTINUATION_CURSOR_MOVED,
    COMPILER_CONTRACT_VIOLATION,
}
