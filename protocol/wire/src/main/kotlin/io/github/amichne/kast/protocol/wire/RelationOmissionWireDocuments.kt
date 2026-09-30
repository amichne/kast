@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationObservedItemsDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionLocationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionSampleRetentionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionSamplesDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.RelationRemediationDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RelationOmissionWireDocument(
    val provider: RelationProviderDocument,
    val reason: RelationLimitationDocument,
    val measurement: RelationOmissionMeasurementWireDocument,
    val samples: RelationOmissionSamplesWireDocument,
    val remediation: RelationRemediationDocument,
)

@Serializable
internal sealed interface RelationOmissionMeasurementWireDocument {
    @Serializable
    @SerialName("observed_on_page")
    data class ObservedOnPage(val items: Long) : RelationOmissionMeasurementWireDocument

    @Serializable
    @SerialName("unmeasured_on_page")
    data object UnmeasuredOnPage : RelationOmissionMeasurementWireDocument
}

@Serializable
internal sealed interface RelationOmissionSamplesWireDocument {
    val locations: List<RelationOmissionLocationWireDocument>

    @Serializable
    @SerialName("complete")
    data class Complete(override val locations: List<RelationOmissionLocationWireDocument>) :
        RelationOmissionSamplesWireDocument

    @Serializable
    @SerialName("truncated")
    data class Truncated(override val locations: List<RelationOmissionLocationWireDocument>) :
        RelationOmissionSamplesWireDocument
}

@Serializable
internal data class RelationOmissionLocationWireDocument(val file: String, val range: SourceRangeWireDocument)

internal fun RelationOmissionDocument.toWireDocument() =
    RelationOmissionWireDocument(
        provider = provider,
        reason = reason,
        measurement =
            when (val observed = measurement) {
                is RelationOmissionMeasurementDocument.ObservedOnPage ->
                    RelationOmissionMeasurementWireDocument.ObservedOnPage(observed.items.value)
                RelationOmissionMeasurementDocument.UnmeasuredOnPage ->
                    RelationOmissionMeasurementWireDocument.UnmeasuredOnPage
            },
        samples = samples.toWireDocument(),
        remediation = remediation,
    )

internal fun RelationOmissionWireDocument.toContract(): WireDocumentConversion<RelationOmissionDocument> =
    measurement.toContract().flatMapConverted { measure ->
        samples.toContract().flatMapConverted { retained ->
            RelationOmissionDocument.create(provider, reason, measure, retained)
                .toWireDocumentConversion()
                .flatMapConverted { admitted ->
                    if (admitted.remediation == remediation) WireDocumentConversion.Converted(admitted)
                    else WireDocumentConversion.Rejected
                }
        }
    }

private fun RelationOmissionMeasurementWireDocument.toContract():
    WireDocumentConversion<RelationOmissionMeasurementDocument> =
    when (this) {
        is RelationOmissionMeasurementWireDocument.ObservedOnPage ->
            RelationObservedItemsDocument.parse(items).toWireDocumentConversion().mapConverted {
                RelationOmissionMeasurementDocument.ObservedOnPage(it)
            }
        RelationOmissionMeasurementWireDocument.UnmeasuredOnPage ->
            WireDocumentConversion.Converted(RelationOmissionMeasurementDocument.UnmeasuredOnPage)
    }

private fun RelationOmissionSamplesDocument.toWireDocument(): RelationOmissionSamplesWireDocument {
    val locations =
        locations.values.map { RelationOmissionLocationWireDocument(it.file.value, it.range.toWireDocument()) }
    return when (retention) {
        RelationOmissionSampleRetentionDocument.COMPLETE -> RelationOmissionSamplesWireDocument.Complete(locations)
        RelationOmissionSampleRetentionDocument.TRUNCATED -> RelationOmissionSamplesWireDocument.Truncated(locations)
    }
}

private fun RelationOmissionSamplesWireDocument.toContract(): WireDocumentConversion<RelationOmissionSamplesDocument> =
    locations
        .convertEach { sample ->
            ProtocolText.parse(sample.file).toWireDocumentConversion().flatMapConverted { file ->
                sample.range.toContract().mapConverted { RelationOmissionLocationDocument(file, it) }
            }
        }
        .flatMapConverted { locations ->
            BoundedProtocolList.create(locations).toWireDocumentConversion().flatMapConverted { bounded ->
                when (this) {
                    is RelationOmissionSamplesWireDocument.Complete -> RelationOmissionSamplesDocument.complete(bounded)
                    is RelationOmissionSamplesWireDocument.Truncated ->
                        RelationOmissionSamplesDocument.truncated(bounded)
                }.toWireDocumentConversion()
            }
        }
