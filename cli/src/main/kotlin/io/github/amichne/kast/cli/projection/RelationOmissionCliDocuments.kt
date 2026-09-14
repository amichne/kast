package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.RelationRemediationDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RelationOmissionCliDocument(
    val provider: RelationProviderDocument,
    val reason: RelationLimitationDocument,
    val measurement: RelationOmissionMeasurementCliDocument,
    val samples: List<RelationOmissionLocationCliDocument>,
    val remediation: RelationRemediationDocument,
)

@Serializable
internal sealed interface RelationOmissionMeasurementCliDocument {
    @Serializable
    @SerialName("observed_on_page")
    data class ObservedOnPage(val items: Long) : RelationOmissionMeasurementCliDocument

    @Serializable
    @SerialName("unmeasured_on_page")
    data object UnmeasuredOnPage : RelationOmissionMeasurementCliDocument
}

@Serializable
internal data class RelationOmissionLocationCliDocument(val file: String, val range: SourceRangeCliDocument)

internal fun RelationOmissionDocument.toCliDocument() =
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
        samples =
            samples.values.map {
                RelationOmissionLocationCliDocument(
                    it.file.value,
                    SourceRangeCliDocument(it.range.startInclusive.value, it.range.endExclusive.value),
                )
            },
        remediation = remediation,
    )
