package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface QueryImmutableCallbackUseDocument {
    val value: QueryImmutableCallbackValueDocument

    data class Supplied(
        val supplier: QueryCallbackParameterSupplierDocument,
        val invocations: BoundedProtocolList<QueryCallbackInvocationDocument>,
        val forwarding: QueryCallbackForwardingEvidenceDocument,
        val summaryOwnerBindings: BoundedProtocolList<QueryCallbackBodyBindingDocument> =
            (BoundedProtocolList.create(emptyList<QueryCallbackBodyBindingDocument>()) as Refinement.Refined).value,
        val summaryObligations: BoundedProtocolList<QueryCallbackFlowCauseDocument> =
            (BoundedProtocolList.create(emptyList<QueryCallbackFlowCauseDocument>()) as Refinement.Refined).value,
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
    val ownerBindings: BoundedProtocolList<QueryCallbackBodyBindingDocument>,
) {
    companion object {
        fun create(
            sourceValue: QueryImmutableCallbackValueDocument,
            uses: BoundedProtocolList<QueryImmutableCallbackUseDocument>,
            obligations: BoundedProtocolList<QueryCallbackFlowCauseDocument>,
            scan: QueryCallbackInvocationScanDocument,
            ownerBindings: BoundedProtocolList<QueryCallbackBodyBindingDocument> =
                (BoundedProtocolList.create(emptyList<QueryCallbackBodyBindingDocument>()) as Refinement.Refined).value,
        ): Refinement<QueryImmutableCallbackFlowDocument, QueryCallbackDocumentFailure> {
            if (sourceValue.source != sourceValue.destination || sourceValue.transfers.values.isNotEmpty())
                return Refinement.Rejected(QueryCallbackDocumentFailure.TRANSFER_PROOF_MISMATCH)
            if (!scan.admitsImmutable(obligations.values))
                return Refinement.Rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
            if (
                uses.values.distinct().size != uses.values.size ||
                    obligations.values != obligations.values.distinct().sortedBy { it.ordinal }
            )
                return Refinement.Rejected(QueryCallbackDocumentFailure.INVALID_SCAN_PROOF)
            val flow = QueryImmutableCallbackFlowDocument(sourceValue, uses, obligations, scan, ownerBindings)
            when (val owners = flow.admitOwnerBindings()) {
                is Refinement.Rejected -> return owners
                is Refinement.Refined -> Unit
            }
            for (use in uses.values) {
                if (!use.value.tracesSource(sourceValue))
                    return Refinement.Rejected(QueryCallbackDocumentFailure.TRANSFER_PROOF_MISMATCH)
                when (
                    val admitted =
                        use.admit(sourceValue.source.enclosing.basis, ownerBindings.values, obligations.values)
                ) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return admitted
                }
            }
            return Refinement.Refined(flow)
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
    basis: ImpactSemanticBasisDocument,
    ownerBindings: List<QueryCallbackBodyBindingDocument>,
    obligations: List<QueryCallbackFlowCauseDocument>,
): Refinement<Unit, QueryCallbackDocumentFailure> =
    when (this) {
        is QueryImmutableCallbackUseDocument.Unused -> Refinement.Refined(Unit)
        is QueryImmutableCallbackUseDocument.Direct -> admitDirect(basis, ownerBindings, obligations)
        is QueryImmutableCallbackUseDocument.Supplied -> {
            val binding = supplier.binding
            if (!supplier.admitsImmutableOwner(ownerBindings, obligations))
                Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
            else if (summaryObligations.values.any { it != QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION })
                Refinement.Rejected(QueryCallbackDocumentFailure.MISSING_OBLIGATION)
            else
                when (val admitted = admitSummaryOwners(basis)) {
                    is Refinement.Rejected -> admitted
                    is Refinement.Refined ->
                        CallbackInvocationRouteProof(
                                basis,
                                invocations.values,
                                summaryObligations.values.filter {
                                    it != QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION || it in obligations
                                },
                                forwarding,
                                summaryOwnerBindings.values,
                            )
                            .admitParameterInvocations(binding.parameterIdentity(), binding.invocation.callable)
                }
        }
    }

internal fun QueryImmutableCallbackUseDocument.Direct.admitDirect(
    basis: ImpactSemanticBasisDocument,
    ownerBindings: List<QueryCallbackBodyBindingDocument> = emptyList(),
    obligations: List<QueryCallbackFlowCauseDocument> = emptyList(),
): Refinement<Unit, QueryCallbackDocumentFailure> {
    val destination = value.destination
    if (
        destination.role != ImpactValueRoleDocument.ExpressionResult &&
            destination.role != ImpactValueRoleDocument.LocalRead
    )
        return Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    if (
        binding.basis != basis || !ownerBindings.admitsImmutableOwner(binding.owner, destination.enclosing, obligations)
    )
        return Refinement.Rejected(QueryCallbackDocumentFailure.BINDING_MISMATCH)
    val owner =
        when (val admitted = binding.owner.admitOwner()) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> admitted.value
        }
    if (!owner.contains(binding.occurrence) || !binding.occurrence.containsImmutableSite(destination))
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

private fun QueryCallbackInvocationScanDocument.admitsImmutable(
    obligations: List<QueryCallbackFlowCauseDocument>
): Boolean =
    when (this) {
        QueryCallbackInvocationScanDocument.NOT_APPLICABLE -> false
        QueryCallbackInvocationScanDocument.EXHAUSTIVE ->
            obligations.all { it == QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION }
        QueryCallbackInvocationScanDocument.INCOMPLETE -> obligations.isNotEmpty()
    }
