package io.github.amichne.kast.protocol.contract

internal fun QueryImmutableCallbackFlowDocument.admitsFactoryPolicy(domain: QueryRelationDomainDocument): Boolean =
    sourceValue.admitsFactoryPolicy(domain) &&
        uses.values.all { it.value.admitsFactoryPolicy(domain) } &&
        executionOwnerBindings().admitsOwnerFactoryPolicy(domain)

internal fun QueryImmutableCallbackValueDocument.admitsFactoryPolicy(domain: QueryRelationDomainDocument): Boolean {
    val pending = ArrayDeque<QueryImmutableCallbackValueDocument>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val value = pending.removeFirst()
        val returned = value.origin as? QueryImmutableCallbackValueOriginDocument.Returned ?: continue
        val factory = returned.factory
        if (!factory.bodyCalls.admitsFactoryPolicy(domain)) return false
        pending.add(factory.returnedValue)
        for (capture in factory.captures.values) when (val content = capture.content) {
            QueryCallbackFactoryCaptureContentDocument.Scalar -> Unit
            is QueryCallbackFactoryCaptureContentDocument.Callable -> pending.addAll(content.values.values)
        }
    }
    return true
}

internal fun QueryCallableTargetDocument.admitsFactoryPolicy(domain: QueryRelationDomainDocument): Boolean =
    when (this) {
        is QueryCallableTargetDocument.DirectInvocations ->
            invocations.values.all { it.value.admitsFactoryPolicy(domain) }
        is QueryCallableTargetDocument.CallbackSupplies -> supplies.values.all { it.value.admitsFactoryPolicy(domain) }
        is QueryCallableTargetDocument.ParameterInvocation ->
            when (val inventory = suppliers) {
                is QueryCallbackSupplierInventoryDocument.Unavailable -> true
                is QueryCallbackSupplierInventoryDocument.Exhaustive ->
                    inventory.partitions.values.all { partition ->
                        partition.suppliers.values.all { it.value.admitsFactoryPolicy(domain) }
                    }
            }
        is QueryCallableTargetDocument.NamedReference ->
            when (val flow = reference.flow) {
                is QueryNamedCallbackReferenceFlowDocument.Immutable -> flow.flow.admitsFactoryPolicy(domain)
                is QueryNamedCallbackReferenceFlowDocument.Direct,
                is QueryNamedCallbackReferenceFlowDocument.Supplied,
                is QueryNamedCallbackReferenceFlowDocument.Unavailable -> true
            }
        is QueryCallableTargetDocument.SourceLess,
        is QueryCallableTargetDocument.UnavailableReference,
        is QueryCallableTargetDocument.UnavailableSupply -> true
    }

private fun QueryCallbackFactoryBodyCallsDocument.admitsFactoryPolicy(domain: QueryRelationDomainDocument): Boolean =
    when (this) {
        QueryCallbackFactoryBodyCallsDocument.NotApplicable -> true
        is QueryCallbackFactoryBodyCallsDocument.Exhaustive ->
            calls.values.all { call ->
                call !is QueryCallbackFactoryBodyCallDocument.Boundary ||
                    call.disposition != QuerySourceLessCallableDispositionDocument.LIBRARY_POLICY_EXCLUDED ||
                    domain.libraries == QueryDiscoveryInclusionPolicyDocument.EXCLUDE
            }
    }

internal fun QueryCallbackFlowDocument.Observed.admitsFactoryPolicy(domain: QueryRelationDomainDocument): Boolean =
    ownerBindings.values.admitsOwnerFactoryPolicy(domain)

private fun List<QueryCallbackBodyBindingDocument>.admitsOwnerFactoryPolicy(
    domain: QueryRelationDomainDocument
): Boolean {
    val pending = ArrayDeque<QueryCallbackBodyBindingDocument>()
    pending.addAll(this)
    while (pending.isNotEmpty()) {
        when (val execution = pending.removeFirst().execution) {
            QueryCallbackOwnerExecutionDocument.Unproven -> Unit
            is QueryCallbackOwnerExecutionDocument.Observed -> pending.addAll(execution.flow.ownerBindings.values)
            is QueryCallbackOwnerExecutionDocument.Immutable -> {
                val flow = execution.flow
                if (
                    !flow.sourceValue.admitsFactoryPolicy(domain) ||
                        !flow.uses.values.all { it.value.admitsFactoryPolicy(domain) }
                )
                    return false
                pending.addAll(flow.executionOwnerBindings())
            }
        }
    }
    return true
}
