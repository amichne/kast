package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionSampleRetentionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionSamplesDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.RelationRemediationDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RelationOmissionCliDocument(
    val provider: RelationProviderDocument,
    val reason: RelationLimitationDocument,
    val measurement: RelationOmissionMeasurementCliDocument,
    val samples: RelationOmissionSamplesCliDocument,
    val remediation: RelationRemediationDocument,
)

@Serializable
sealed interface RelationOmissionMeasurementCliDocument {
    @Serializable
    @SerialName("observed_on_page")
    data class ObservedOnPage(val items: Long) : RelationOmissionMeasurementCliDocument

    @Serializable
    @SerialName("unmeasured_on_page")
    data object UnmeasuredOnPage : RelationOmissionMeasurementCliDocument
}

@Serializable
sealed interface RelationOmissionSamplesCliDocument {
    val locations: List<RelationOmissionLocationCliDocument>

    @Serializable
    @SerialName("complete")
    data class Complete(override val locations: List<RelationOmissionLocationCliDocument>) :
        RelationOmissionSamplesCliDocument

    @Serializable
    @SerialName("truncated")
    data class Truncated(override val locations: List<RelationOmissionLocationCliDocument>) :
        RelationOmissionSamplesCliDocument
}

@Serializable data class RelationOmissionLocationCliDocument(val file: String, val range: SourceRangeCliDocument)

fun RelationOmissionDocument.toCliDocument() =
    RelationOmissionCliDocument(
        provider = provider,
        reason = reason,
        measurement =
            when (val observed = measurement) {
                is RelationOmissionMeasurementDocument.ObservedOnPage ->
                    RelationOmissionMeasurementCliDocument.ObservedOnPage(observed.items.value)
                RelationOmissionMeasurementDocument.UnmeasuredOnPage ->
                    RelationOmissionMeasurementCliDocument.UnmeasuredOnPage
            },
        samples = samples.toCliDocument(),
        remediation = remediation,
    )

private fun RelationOmissionSamplesDocument.toCliDocument(): RelationOmissionSamplesCliDocument {
    val locations =
        locations.values.map {
            RelationOmissionLocationCliDocument(
                it.file.value,
                SourceRangeCliDocument(it.range.startInclusive.value, it.range.endExclusive.value),
            )
        }
    return when (retention) {
        RelationOmissionSampleRetentionDocument.COMPLETE -> RelationOmissionSamplesCliDocument.Complete(locations)
        RelationOmissionSampleRetentionDocument.TRUNCATED -> RelationOmissionSamplesCliDocument.Truncated(locations)
    }
}
