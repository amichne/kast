package io.github.amichne.kast.protocol.contract

sealed interface QueryCallbackResultSiteDocument {
    data class Occurrence(val occurrence: RelationOccurrenceDocument) : QueryCallbackResultSiteDocument

    data class Invocation(val invocation: QueryCallbackInvocationDocument) : QueryCallbackResultSiteDocument

    data class DirectInvocation(val binding: QueryCallbackBindingDocument.Direct) : QueryCallbackResultSiteDocument

    data class Target(val target: QueryCallbackCallableDocument) : QueryCallbackResultSiteDocument

    data class AnonymousTarget(val body: QueryCallbackBodyDocument.Anonymous) : QueryCallbackResultSiteDocument
}

/** The proof universe and the exact site admitted into the selected result domain remain separate. */
sealed interface QueryCallbackResultProjectionDocument {
    fun admitsSubject(subject: QueryReferenceDocument.ExactSymbol): Boolean =
        when (this) {
            is Admitted -> this.subject == subject
            is Unproven -> true
        }

    fun admitsRelation(relation: RelationKindDocument): Boolean =
        when (this) {
            is Admitted -> this.relation == relation
            is Unproven -> true
        }

    data class Admitted(
        val subject: QueryReferenceDocument.ExactSymbol,
        val basis: ImpactSemanticBasisDocument,
        val domain: QueryRelationDomainFingerprint,
        val relation: RelationKindDocument,
        val site: QueryCallbackResultSiteDocument,
    ) : QueryCallbackResultProjectionDocument

    data class Unproven(val cause: QueryCallbackFlowCauseDocument) : QueryCallbackResultProjectionDocument

    fun admitsCallback(
        relation: RelationKindDocument,
        domain: QueryRelationDomainFingerprint,
        occurrence: RelationOccurrenceDocument,
        target: QueryCallbackCallableDocument,
        flow: QueryCallbackFlowDocument,
    ): Boolean =
        when (this) {
            is Unproven -> true
            is Admitted ->
                this.relation == relation &&
                    this.domain == domain &&
                    flow.admitsBasis(basis) &&
                    site in callbackResultSites(relation, occurrence, target, flow)
        }

    fun admitsReference(
        domain: QueryRelationDomainFingerprint,
        occurrence: RelationOccurrenceDocument,
        reference: QueryNamedCallbackReferenceDocument,
    ): Boolean =
        when (this) {
            is Unproven -> true
            is Admitted ->
                this.domain == domain &&
                    reference.flow.admitsBasis(basis) &&
                    site in referenceResultSites(relation, occurrence, reference)
        }

    fun admitsSuppliers(
        domain: QueryRelationDomainFingerprint,
        suppliers: QueryCallbackSupplierInventoryDocument,
    ): Boolean =
        when (this) {
            is Unproven -> true
            is Admitted ->
                relation == RelationKindDocument.CALLEES &&
                    suppliers is QueryCallbackSupplierInventoryDocument.Exhaustive &&
                    this.domain == domain &&
                    basis == suppliers.basis &&
                    site in
                        suppliers.partitions.values.flatMap { partition ->
                            partition.suppliers.values.flatMap { resultTargets(it.value) }
                        }
        }
}

private fun callbackResultSites(
    relation: RelationKindDocument,
    occurrence: RelationOccurrenceDocument,
    target: QueryCallbackCallableDocument,
    flow: QueryCallbackFlowDocument,
): List<QueryCallbackResultSiteDocument> =
    when (relation) {
        RelationKindDocument.CALLERS ->
            listOf(QueryCallbackResultSiteDocument.Occurrence(occurrence)) +
                when (flow) {
                    is QueryCallbackFlowDocument.Observed ->
                        flow.invocations.values.map(QueryCallbackResultSiteDocument::Invocation) +
                            when (val binding = flow.binding) {
                                is QueryCallbackBindingDocument.Direct ->
                                    listOf(QueryCallbackResultSiteDocument.DirectInvocation(binding))
                                is QueryCallbackBindingDocument.Bound,
                                is QueryCallbackBindingDocument.Default,
                                is QueryCallbackBindingDocument.DependencyContract,
                                is QueryCallbackBindingDocument.DeferredDependency,
                                is QueryCallbackBindingDocument.Unavailable -> emptyList()
                            }
                    is QueryCallbackFlowDocument.Immutable -> resultInvocations(flow.flow)
                    is QueryCallbackFlowDocument.Unavailable,
                    is QueryCallbackFlowDocument.ContractRejected -> emptyList()
                }
        RelationKindDocument.CALLEES -> listOf(QueryCallbackResultSiteDocument.Target(target))
        RelationKindDocument.REFERENCES,
        RelationKindDocument.TYPE_USES,
        RelationKindDocument.IMPLEMENTATIONS,
        RelationKindDocument.INHERITORS,
        RelationKindDocument.OVERRIDES -> emptyList()
    }

private fun referenceResultSites(
    relation: RelationKindDocument,
    occurrence: RelationOccurrenceDocument,
    reference: QueryNamedCallbackReferenceDocument,
): List<QueryCallbackResultSiteDocument> =
    when (relation) {
        RelationKindDocument.CALLERS ->
            listOf(QueryCallbackResultSiteDocument.Occurrence(occurrence)) +
                when (val flow = reference.flow) {
                    is QueryNamedCallbackReferenceFlowDocument.Supplied ->
                        flow.invocations.values.map(QueryCallbackResultSiteDocument::Invocation)
                    is QueryNamedCallbackReferenceFlowDocument.Direct ->
                        listOf(QueryCallbackResultSiteDocument.DirectInvocation(flow.binding))
                    is QueryNamedCallbackReferenceFlowDocument.Immutable -> resultInvocations(flow.flow)
                    is QueryNamedCallbackReferenceFlowDocument.Unavailable -> emptyList()
                }
        RelationKindDocument.CALLEES -> listOf(QueryCallbackResultSiteDocument.Target(reference.target))
        RelationKindDocument.REFERENCES,
        RelationKindDocument.TYPE_USES,
        RelationKindDocument.IMPLEMENTATIONS,
        RelationKindDocument.INHERITORS,
        RelationKindDocument.OVERRIDES -> emptyList()
    }

private fun resultTargets(value: QueryImmutableCallbackValueDocument): List<QueryCallbackResultSiteDocument> =
    when (val origin = value.origin) {
        is QueryImmutableCallbackValueOriginDocument.Named ->
            listOf(QueryCallbackResultSiteDocument.Target(origin.target))
        is QueryImmutableCallbackValueOriginDocument.Anonymous ->
            listOf(QueryCallbackResultSiteDocument.AnonymousTarget(origin.body))
        is QueryImmutableCallbackValueOriginDocument.Returned -> resultTargets(origin.factory.returnedValue)
    }

private fun resultInvocations(flow: QueryImmutableCallbackFlowDocument): List<QueryCallbackResultSiteDocument> =
    flow.uses.values.flatMap { use ->
        when (use) {
            is QueryImmutableCallbackUseDocument.Direct ->
                listOf(QueryCallbackResultSiteDocument.DirectInvocation(use.binding))
            is QueryImmutableCallbackUseDocument.Supplied ->
                use.invocations.values.map(QueryCallbackResultSiteDocument::Invocation)
            is QueryImmutableCallbackUseDocument.Unused -> emptyList()
        }
    }

private fun QueryCallbackFlowDocument.admitsBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is QueryCallbackFlowDocument.Observed -> this.basis == basis
        is QueryCallbackFlowDocument.Immutable -> flow.sourceValue.source.enclosing.basis == basis
        is QueryCallbackFlowDocument.Unavailable,
        is QueryCallbackFlowDocument.ContractRejected -> true
    }

private fun QueryNamedCallbackReferenceFlowDocument.admitsBasis(basis: ImpactSemanticBasisDocument): Boolean =
    when (this) {
        is QueryNamedCallbackReferenceFlowDocument.Supplied -> binding.invocation.callable.basis == basis
        is QueryNamedCallbackReferenceFlowDocument.Direct -> binding.basis == basis
        is QueryNamedCallbackReferenceFlowDocument.Immutable -> flow.sourceValue.source.enclosing.basis == basis
        is QueryNamedCallbackReferenceFlowDocument.Unavailable -> true
    }
