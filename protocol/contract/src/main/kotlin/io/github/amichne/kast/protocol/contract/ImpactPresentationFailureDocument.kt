@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
enum class ImpactPresentationTextCause {
    BLANK,
    TOO_LONG,
}

@Serializable
enum class ImpactPresentationOffsetCause {
    NEGATIVE
}

@Serializable
enum class ImpactPresentationModelCause {
    INVALID_IDENTIFIER,
    NOT_POSITIVE,
    UNSUPPORTED_FORMAT,
}

@Serializable
enum class ImpactPresentationCollectionCause {
    TOO_LARGE
}

@Serializable
enum class ImpactPresentationFingerprintCause {
    INVALID_FINGERPRINT
}

@Serializable
enum class ImpactPresentationCountCause {
    NEGATIVE
}

@Serializable
enum class ImpactPresentationBudgetCause {
    NOT_POSITIVE
}

/** Finite projection failures preserve their originating boundary and exact refinement rejection. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ImpactPresentationFailureDocument {
    @Serializable
    @SerialName("ACCOUNTING")
    data class Accounting(val cause: ImpactAccountingFailure) : ImpactPresentationFailureDocument

    @Serializable
    @SerialName("TEXT")
    data class Text(val cause: ImpactPresentationTextCause) : ImpactPresentationFailureDocument

    @Serializable
    @SerialName("OFFSET")
    data class Offset(val cause: ImpactPresentationOffsetCause) : ImpactPresentationFailureDocument

    @Serializable
    @SerialName("MODEL")
    data class Model(val cause: ImpactPresentationModelCause) : ImpactPresentationFailureDocument

    @Serializable
    @SerialName("COLLECTION")
    data class Collection(val cause: ImpactPresentationCollectionCause) : ImpactPresentationFailureDocument

    @Serializable
    @SerialName("DOMAIN_FINGERPRINT")
    data class DomainFingerprint(val cause: ImpactPresentationFingerprintCause) : ImpactPresentationFailureDocument

    @Serializable
    @SerialName("COUNT")
    data class Count(val cause: ImpactPresentationCountCause) : ImpactPresentationFailureDocument

    @Serializable
    @SerialName("BUDGET")
    data class Budget(val cause: ImpactPresentationBudgetCause) : ImpactPresentationFailureDocument

    @Serializable
    @SerialName("DOMAIN_PROJECTION_REJECTED")
    data object DomainProjectionRejected : ImpactPresentationFailureDocument
}
