package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface QueryImmutableCallbackUseDocument {
    val value: QueryImmutableCallbackValueDocument

    data class Supplied(
        val supplier: QueryCallbackParameterSupplierDocument,
        val invocations: BoundedProtocolList<QueryCallbackInvocationDocument>,
        val forwarding: QueryCallbackForwardingEvidenceDocument,
    ) : QueryImmutableCallbackUseDocument {
        override val value: QueryImmutableCallbackValueDocument
            get() = supplier.value
    }

    data class Direct(
        override val value: QueryImmutableCallbackValueDocument,
        val binding: QueryCallbackBindingDocument.Direct,
    ) : QueryImmutableCallbackUseDocument

    data class Unused(override val value: QueryImmutableCallbackValueDocument) : QueryImmutableCallbackUseDocument
}

@ConsistentCopyVisibility
data class QueryImmutableCallbackFlowDocument
private constructor(
    val sourceValue: QueryImmutableCallbackValueDocument,
    val uses: BoundedProtocolList<QueryImmutableCallbackUseDocument>,
    val obligations: BoundedProtocolList<QueryCallbackFlowCauseDocument>,
    val scan: QueryCallbackInvocationScanDocument,
) {
    companion object {
        fun create(
            sourceValue: QueryImmutableCallbackValueDocument,
            uses: BoundedProtocolList<QueryImmutableCallbackUseDocument>,
            obligations: BoundedProtocolList<QueryCallbackFlowCauseDocument>,
            scan: QueryCallbackInvocationScanDocument,
        ): Refinement<QueryImmutableCallbackFlowDocument, QueryCallbackDocumentFailure> {
            if (sourceValue.source != sourceValue.destination || sourceValue.transfers.values.isNotEmpty())
                return Refinement.Rejected(QueryCallbackDocumentFailure.TRANSFER_PROOF_MISMATCH)
            if (
                scan == QueryCallbackInvocationScanDocument.NOT_APPLICABLE ||
                    (scan == QueryCallbackInvocationScanDocument.EXHAUSTIVE) != obligations.values.isEmpty()
            )
                return Refinement.Rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
            if (
                uses.values.distinct().size != uses.values.size ||
                    obligations.values != obligations.values.distinct().sortedBy { it.ordinal }
            )
                return Refinement.Rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
            for (use in uses.values) {
                if (!use.value.tracesSource(sourceValue))
                    return Refinement.Rejected(QueryCallbackDocumentFailure.TRANSFER_PROOF_MISMATCH)
                when (val admitted = use.admit(sourceValue.source.enclosing.basis)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return admitted
                }
            }
            return Refinement.Refined(QueryImmutableCallbackFlowDocument(sourceValue, uses, obligations, scan))
        }
    }
}

private fun QueryImmutableCallbackValueDocument.tracesSource(
    sourceValue: QueryImmutableCallbackValueDocument
): Boolean {
    val pending = ArrayDeque<QueryImmutableCallbackValueDocument>().apply { add(this@tracesSource) }
    while (pending.isNotEmpty()) {
        val value = pending.removeFirst()
        if (value.source == sourceValue.source && value.origin == sourceValue.origin) return true
        pending.addAll(value.factorySources())
    }
    return false
}

private fun QueryImmutableCallbackUseDocument.admit(
    basis: ImpactSemanticBasisDocument
): Refinement<Unit, QueryCallbackDocumentFailure> =
    when (this) {
        is QueryImmutableCallbackUseDocument.Unused -> Refinement.Refined(Unit)
        is QueryImmutableCallbackUseDocument.Direct -> admitDirect(basis)
        is QueryImmutableCallbackUseDocument.Supplied -> {
            val binding = supplier.binding
            if (binding.invocationOwner !is QueryCallbackBodyDocument.Named)
                Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
            else
                CallbackInvocationRouteProof(basis, invocations.values, emptyList(), forwarding)
                    .admitParameterInvocations(binding.parameterIdentity(), binding.invocation.callable)
        }
    }

internal fun QueryImmutableCallbackUseDocument.Direct.admitDirect(
    basis: ImpactSemanticBasisDocument
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val owner =
        binding.owner as? QueryCallbackBodyDocument.Named
            ?: return Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    val destination = value.destination
    if (
        destination.role != ImpactValueRoleDocument.ExpressionResult &&
            destination.role != ImpactValueRoleDocument.LocalRead
    )
        return Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    if (binding.basis != basis || owner.callable.reference(binding.basis) != destination.enclosing)
        return Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    if (!binding.occurrence.containsImmutableSite(destination))
        return Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    return Refinement.Refined(Unit)
}

private fun RelationOccurrenceDocument.containsImmutableSite(site: ImpactValueSiteReferenceDocument): Boolean =
    file == site.enclosing.file &&
        range.startInclusive.value <= site.range.start.value &&
        range.endExclusive.value >= site.range.end.value

private fun QueryImmutableCallbackValueDocument.factorySources(): List<QueryImmutableCallbackValueDocument> =
    when (val origin = origin) {
        is QueryImmutableCallbackValueOriginDocument.Anonymous,
        is QueryImmutableCallbackValueOriginDocument.Named -> emptyList()
        is QueryImmutableCallbackValueOriginDocument.Returned ->
            listOf(origin.factory.returnedValue) + origin.factory.captures.values.flatMap { it.invokedSources() }
    }

private fun QueryCallbackFactoryCaptureDocument.invokedSources(): List<QueryImmutableCallbackValueDocument> =
    when (val content = content) {
        QueryCallbackFactoryCaptureContentDocument.Scalar -> emptyList()
        is QueryCallbackFactoryCaptureContentDocument.Callable ->
            if (content.invocations.values.isEmpty()) emptyList() else content.values.values
    }
