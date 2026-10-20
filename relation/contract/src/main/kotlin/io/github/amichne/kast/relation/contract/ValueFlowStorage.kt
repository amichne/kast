package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

internal fun valueSiteStorageBytes(site: ValueSite): Long = ValueFootprintMeter().site(site)

internal fun valueProducerSeedStorageBytes(seed: ValueProducerSeed): Long = ValueFootprintMeter().seed(seed)

internal fun valueReviewedCallbackStorageBytes(target: ValueReviewedCallbackTarget): Long =
    ValueFootprintMeter().reviewedCallbackTarget(target)

internal fun valueFlowStorageBytes(
    source: ValueSite,
    domain: RelationRequest,
    transfers: List<ValueTransfer>,
    obligations: List<ValueFlowObligation>,
    remainder: LocalBindingReadRemainder? = null,
    applications: List<ValueModelApplication> = emptyList(),
): RelationByteCount {
    val meter = ValueFootprintMeter()
    return meter
        .flow(source, domain, transfers, obligations)
        .addBytes(
            applications.fold(0L) { bytes, application -> bytes.addBytes(meter.application(application, source)) }
        )
        .addBytes(remainder?.let(meter::remainder) ?: 0L)
        .byteCount()
}

internal fun localBindingRemainderStorageBytes(remainder: LocalBindingReadRemainder): Long =
    ValueFootprintMeter().remainder(remainder)

internal fun localReferenceCandidateStorageBytes(
    source: ValueSite,
    domain: RelationRequest,
    transfers: List<ValueTransfer>,
    obligations: List<ValueFlowObligation>,
    consumed: Set<LocalReferenceKey>,
    emitted: Set<ValueSiteIdentity>,
): RelationByteCount {
    val meter = ValueFootprintMeter()
    return meter
        .flow(source, domain, transfers, obligations)
        .addBytes(VALUE_RECORD_OVERHEAD)
        .addBytes(meter.localReadSite(source))
        .addBytes(meter.referenceRemainder(source, domain.boundary, consumed, emitted))
        .addBytes(LOCAL_REFERENCE_KEY_STORAGE_BYTES)
        .addBytes(meter.localReadIdentity(source))
        .byteCount()
}

private fun Long.byteCount(): RelationByteCount =
    when (val count = RelationByteCount.parse(this)) {
        is Refinement.Refined -> count.value
        is Refinement.Rejected -> error("Saturating detached byte count cannot be negative")
    }

private fun ValueFootprintMeter.flow(
    source: ValueSite,
    domain: RelationRequest,
    transfers: List<ValueTransfer>,
    obligations: List<ValueFlowObligation>,
): Long =
    VALUE_RECORD_OVERHEAD.addBytes(site(source))
        .addBytes(domain.subject.detachedTextUnits().multiplyBytes(2))
        .addBytes(domain.boundary.canonical(domain.subject).length.toLong().multiplyBytes(2))
        .addBytes(
            transfers.fold(0L) { sum, edge ->
                sum.addBytes(VALUE_RECORD_OVERHEAD)
                    .addBytes(evidence(edge.evidence))
                    .addBytes(if (edge.source === source) 0L else site(edge.source))
                    .addBytes(site(edge.target))
            }
        )
        .addBytes(
            obligations.fold(0L) { sum, obligation ->
                sum.addBytes(VALUE_RECORD_OVERHEAD)
                    .addBytes(if (obligation.site === source) 0L else site(obligation.site))
                    .addBytes(obligationEvidence(obligation.evidence))
            }
        )

internal fun Long.addBytes(other: Long): Long = if (other > Long.MAX_VALUE - this) Long.MAX_VALUE else this + other

internal fun Long.multiplyBytes(other: Long): Long = if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

internal fun sourceEvidenceStorageBytes(operation: ValueSourceOperation): Long =
    ValueFootprintMeter().operation(operation)

internal fun valuePredicateStorageBytes(predicates: ValuePredicates): Long =
    ValueFootprintMeter().predicates(predicates)
