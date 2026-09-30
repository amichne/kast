package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationObservedItemsDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionLocationDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionMeasurementDocument
import io.github.amichne.kast.protocol.contract.RelationOmissionSamplesDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationOmissionMeasurement
import io.github.amichne.kast.relation.contract.RelationOmissionSampleRetention
import io.github.amichne.kast.relation.contract.RelationOmissionSamples

internal fun RelationBatch.protocolOmissions(
    limitations: Set<RelationLimitation>
): BoundedProtocolList<RelationOmissionDocument>? {
    val records = omissions.associateBy { it.reason }
    val documents =
        limitations
            .sortedBy { it.ordinal }
            .map { reason ->
                val record =
                    records[reason]
                        ?: RelationOmissionEvidence.unmeasured(
                            provider = request.providerCursor.provider,
                            reason = reason,
                        )
                record.protocolDocument() ?: return null
            }
    return BoundedProtocolList.create(documents).valueOrNull()
}

internal fun RelationOmissionEvidence.protocolDocument(): RelationOmissionDocument? {
    val measure =
        when (val measured = measurement) {
            is RelationOmissionMeasurement.ObservedOnPage ->
                RelationOmissionMeasurementDocument.ObservedOnPage(
                    RelationObservedItemsDocument.parse(measured.items.value).valueOrNull() ?: return null
                )
            RelationOmissionMeasurement.UnmeasuredOnPage -> RelationOmissionMeasurementDocument.UnmeasuredOnPage
        }
    val locations =
        samples.locations.map { sample ->
            RelationOmissionLocationDocument(
                ProtocolText.parse(sample.file.stableValue).valueOrNull() ?: return null,
                SourceRangeDocument.create(
                        ProtocolOffset.parse(sample.range.startInclusive).valueOrNull() ?: return null,
                        ProtocolOffset.parse(sample.range.endExclusive).valueOrNull() ?: return null,
                    )
                    .valueOrNull() ?: return null,
            )
        }
    return RelationOmissionDocument.create(
            provider = RelationProviderDocument.valueOf(provider.name),
            reason = RelationLimitationDocument.valueOf(reason.name),
            measurement = measure,
            samples = samples.protocolDocument(locations) ?: return null,
        )
        .valueOrNull()
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

private fun RelationOmissionSamples.protocolDocument(
    locations: List<RelationOmissionLocationDocument>
): RelationOmissionSamplesDocument? {
    val bounded = BoundedProtocolList.create(locations).valueOrNull() ?: return null
    return when (retention) {
        RelationOmissionSampleRetention.COMPLETE -> RelationOmissionSamplesDocument.complete(bounded)
        RelationOmissionSampleRetention.TRUNCATED -> RelationOmissionSamplesDocument.truncated(bounded)
    }.valueOrNull()
}
