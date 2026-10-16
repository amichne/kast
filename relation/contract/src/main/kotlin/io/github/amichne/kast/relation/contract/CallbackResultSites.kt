package io.github.amichne.kast.relation.contract

/** Candidate sites come only from retained compiler identities and invocation proofs. */
object CallbackResultSites {
    fun callback(
        meaning: RelationMeaning,
        occurrence: RelationOccurrence,
        target: io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence,
        flow: CallbackInvocationFlowRead,
    ): List<CallbackResultSite> =
        when (meaning) {
            RelationMeaning.Callers ->
                listOf(CallbackResultSite.Occurrence(occurrence)) +
                    when (flow) {
                        is CallbackInvocationFlowRead.Observed ->
                            flow.flow.invocations.map(CallbackResultSite::Invocation) +
                                when (val binding = flow.flow.binding) {
                                    is CallbackBindingEvidence.Direct ->
                                        listOf(CallbackResultSite.DirectInvocation(binding.binding))
                                    is CallbackBindingEvidence.Bound,
                                    is CallbackBindingEvidence.Default,
                                    is CallbackBindingEvidence.DeferredDependency,
                                    is CallbackBindingEvidence.DependencyContract,
                                    is CallbackBindingEvidence.Unavailable -> emptyList()
                                }
                        is CallbackInvocationFlowRead.Immutable -> invocations(flow.flow)
                        is CallbackInvocationFlowRead.Unavailable,
                        is CallbackInvocationFlowRead.ContractRejected -> emptyList()
                    }
            RelationMeaning.Callees -> listOf(CallbackResultSite.Target(target))
            RelationMeaning.References,
            RelationMeaning.TypeUses,
            RelationMeaning.Implementations,
            RelationMeaning.Inheritors,
            RelationMeaning.Overrides -> emptyList()
        }

    fun reference(meaning: RelationMeaning, reference: NamedCallbackReference): List<CallbackResultSite> =
        when (meaning) {
            RelationMeaning.Callers ->
                listOf(CallbackResultSite.Occurrence(reference.occurrence)) +
                    when (val flow = reference.flow) {
                        is NamedCallbackReferenceFlow.Supplied ->
                            flow.summary.invocations.map(CallbackResultSite::Invocation)
                        is NamedCallbackReferenceFlow.Direct ->
                            listOf(CallbackResultSite.DirectInvocation(flow.binding))
                        is NamedCallbackReferenceFlow.Immutable -> invocations(flow.flow)
                        is NamedCallbackReferenceFlow.Unavailable -> emptyList()
                    }
            RelationMeaning.Callees -> listOf(CallbackResultSite.Target(reference.target.evidence))
            RelationMeaning.References,
            RelationMeaning.TypeUses,
            RelationMeaning.Implementations,
            RelationMeaning.Inheritors,
            RelationMeaning.Overrides -> emptyList()
        }

    fun suppliers(inventory: CompleteCallbackSupplierInventory): List<CallbackResultSite> =
        inventory.partitions.flatMap { partition -> partition.suppliers.flatMap { targets(it.value) } }.distinct()

    private fun targets(value: ImmutableCallbackValue): List<CallbackResultSite> =
        when (val origin = value.origin) {
            is ImmutableCallbackValueOrigin.Named -> listOf(CallbackResultSite.Target(origin.target.evidence))
            is ImmutableCallbackValueOrigin.Anonymous -> listOf(CallbackResultSite.AnonymousTarget(origin.body))
            is ImmutableCallbackValueOrigin.Returned -> targets(origin.factory.returnedValue)
        }

    private fun invocations(flow: ImmutableCallbackInvocationFlow): List<CallbackResultSite> =
        flow.uses.flatMap { use ->
            when (use) {
                is ImmutableCallbackInvocationUse.Direct -> listOf(CallbackResultSite.DirectInvocation(use.binding))
                is ImmutableCallbackInvocationUse.Supplied ->
                    use.summary.invocations.map(CallbackResultSite::Invocation)
                is ImmutableCallbackInvocationUse.Unused -> emptyList()
            }
        }
}
