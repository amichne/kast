package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationObservedItemsDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionLocationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.RelationRemediationDocument
import io.github.amichne.kast.protocol.contract.RelationSoundnessDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RelationOmissionWireDocument(
    val provider: RelationProviderDocument,
    val reason: RelationLimitationDocument,
    val measurement: RelationOmissionMeasurementWireDocument,
    val samples: List<RelationOmissionLocationWireDocument>,
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
        samples = samples.values.map { RelationOmissionLocationWireDocument(it.file.value, it.range.toWireDocument()) },
        remediation = remediation,
    )

internal fun RelationOmissionWireDocument.toContract(): WireDocumentConversion<RelationOmissionDocument> =
    measurement.toContract().flatMapConverted { measure ->
        samples
            .convertEach { sample ->
                ProtocolText.parse(sample.file).toWireDocumentConversion().flatMapConverted { file ->
                    sample.range.toContract().mapConverted { RelationOmissionLocationDocument(file, it) }
                }
            }
            .flatMapConverted { locations ->
                BoundedProtocolList.create(locations).toWireDocumentConversion().flatMapConverted { bounded ->
                    RelationOmissionDocument.create(
                            provider = provider,
                            reason = reason,
                            measurement = measure,
                            samples = bounded,
                        )
                        .toWireDocumentConversion()
                        .flatMapConverted { admitted ->
                            if (admitted.remediation == remediation) WireDocumentConversion.Converted(admitted)
                            else WireDocumentConversion.Rejected
                        }
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

@Serializable
internal data class RelationReadResultWireDocument(
    val relations: List<RelationFactWireDocument>,
    val omissions: List<RelationOmissionWireDocument>,
    val soundness: io.github.amichne.kast.protocol.contract.RelationSoundnessDocument,
)

internal fun RelationReadResult.toSymbolWireDocument() =
    RelationReadResultWireDocument(
        relations.values.map { it.toWireDocument() },
        omissions.values.map { it.toWireDocument() },
        soundness,
    )

/**
 * `RelationReadResultWireDocument -> RelationReadResult` establishes a bounded exact-symbol list; invalid raw fields
 * become `WireFailure.InvalidPayload` at this wire boundary.
 */
internal fun RelationReadResultWireDocument.toContract(): WireDocumentConversion<RelationReadResult> =
    relations
        .convertEach { it.toContract() }
        .flatMapConverted { values -> BoundedProtocolList.create(values).toWireDocumentConversion() }
        .flatMapConverted { relations ->
            omissions
                .convertEach { it.toContract() }
                .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() }
                .mapConverted { RelationReadResult(relations, it) }
        }
