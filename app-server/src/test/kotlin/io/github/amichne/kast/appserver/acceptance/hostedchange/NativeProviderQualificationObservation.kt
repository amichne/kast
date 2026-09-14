package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.provider.KastProviderQualification
import io.github.amichne.kast.appserver.provider.KastQualificationFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bounded evidence from the actual qualifier; no schemas, command output, paths, or version strings. */
@Serializable
internal sealed interface NativeProviderQualificationObservation {
    @Serializable
    @SerialName("admitted")
    data class Admitted(val stage: NativeQualificationStage = NativeQualificationStage.PROVIDER_QUALIFICATION) :
        NativeProviderQualificationObservation

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val cause: NativeQualificationCause,
        val stage: NativeQualificationStage = NativeQualificationStage.PROVIDER_QUALIFICATION,
    ) : NativeProviderQualificationObservation
}

@Serializable
internal enum class NativeQualificationStage {
    PROVIDER_QUALIFICATION
}

@Serializable
internal enum class NativeQualificationCause {
    VERSION_UNAVAILABLE,
    VERSION_INVALID,
    SCHEMA_UNAVAILABLE,
    SCHEMA_SIZE_LIMIT,
    SCHEMA_INVALID,
    SCHEMA_INCOMPATIBLE,
}

internal class NativeProviderQualificationRejected(val failure: KastQualificationFailure) :
    RuntimeException(failure.name)

internal fun KastProviderQualification.nativeQualified(
    observe: (NativeProviderQualificationObservation) -> Unit
): KastProviderQualification.Qualified =
    when (this) {
        is KastProviderQualification.Qualified -> {
            observe(NativeProviderQualificationObservation.Admitted())
            this
        }
        is KastProviderQualification.Rejected -> {
            observe(NativeProviderQualificationObservation.Rejected(failure.observedCause()))
            throw NativeProviderQualificationRejected(failure)
        }
    }

internal fun NativeProviderQualificationObservation.encodeObservation(): String =
    qualificationObservationJson.encodeToString(this)

private val qualificationObservationJson = Json {
    classDiscriminator = "outcome"
    encodeDefaults = true
}

private fun KastQualificationFailure.observedCause(): NativeQualificationCause =
    when (this) {
        KastQualificationFailure.VERSION_UNAVAILABLE -> NativeQualificationCause.VERSION_UNAVAILABLE
        KastQualificationFailure.VERSION_INVALID -> NativeQualificationCause.VERSION_INVALID
        KastQualificationFailure.SCHEMA_UNAVAILABLE -> NativeQualificationCause.SCHEMA_UNAVAILABLE
        KastQualificationFailure.SCHEMA_SIZE_LIMIT -> NativeQualificationCause.SCHEMA_SIZE_LIMIT
        KastQualificationFailure.SCHEMA_INVALID -> NativeQualificationCause.SCHEMA_INVALID
        KastQualificationFailure.SCHEMA_INCOMPATIBLE -> NativeQualificationCause.SCHEMA_INCOMPATIBLE
    }
